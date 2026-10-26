package com.bilpark.backend;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Audit hooks (login, check-in/out, tariffs, ...) and the admin audit list / export. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private AuditService auditService;
    @Autowired private UserRepository users;

    private String login(String user, String pass, String device) throws Exception {
        var req = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}");
        if (device != null) req.header("X-Device-Id", device);
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass", null); }

    @Test
    void officerAndUnauthenticatedGetRefused() throws Exception {
        String off = login("tevfik", "test-officer-pass", "dev-aud-403");
        for (String url : new String[]{"/api/admin/audit", "/api/admin/audit/actions", "/api/admin/audit/export"}) {
            mvc.perform(get(url).header("Authorization", off)).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void checkOutLeavesRowWithDeviceId_andFiltersWork() throws Exception {
        String device = "dev-aud-" + UUID.randomUUID().toString().substring(0, 8);
        String off = login("tevfik", "test-officer-pass", device);
        String adm = admin();
        String plate = RecordCancellationIntegrationTest.plate();

        mvc.perform(post("/api/parking/check-in").header("Authorization", off).header("X-Device-Id", device)
                        .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", off).header("X-Device-Id", device)
                        .param("plate", plate).param("paymentMethod", "POS"))
                .andExpect(status().isOk());

        // check-out row: actor, device, summary and after-JSON object
        mvc.perform(get("/api/admin/audit").header("Authorization", adm)
                        .param("action", "VEHICLE_CHECK_OUT").param("q", plate.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].deviceId").value(device))
                .andExpect(jsonPath("$.content[0].actorUsername").value("tevfik"))
                .andExpect(jsonPath("$.content[0].actorRole").value("OFFICER"))
                .andExpect(jsonPath("$.content[0].entityType").value("ParkingRecord"))
                .andExpect(jsonPath("$.content[0].summary").value(org.hamcrest.Matchers.containsString("(POS)")))
                .andExpect(jsonPath("$.content[0].after.plate").value(plate))
                .andExpect(jsonPath("$.content[0].after.paymentMethod").value("POS"))
                .andExpect(jsonPath("$.content[0].before").isEmpty());

        // comma separated actions, newest first (check-out after check-in)
        mvc.perform(get("/api/admin/audit").header("Authorization", adm)
                        .param("action", "VEHICLE_CHECK_IN,VEHICLE_CHECK_OUT").param("q", plate))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].action").value("VEHICLE_CHECK_OUT"))
                .andExpect(jsonPath("$.content[1].action").value("VEHICLE_CHECK_IN"))
                .andExpect(jsonPath("$.content[1].entityType").value("ParkSpot"));

        // other filters
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("q", plate).param("actor", "TEVFIK"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("q", plate).param("actor", "admin"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("q", plate).param("actorRole", "ADMIN"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("q", plate).param("entityType", "ParkSpot"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("q", plate)
                        .param("from", "2001-01-01").param("to", "2001-01-31"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("q", plate).param("size", "1"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("action", "NOPE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("from", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runawayAndLoginRowsExist_failedLoginIsAudited() throws Exception {
        String device = "dev-ra-" + UUID.randomUUID().toString().substring(0, 8);
        String off = login("tevfik", "test-officer-pass", device);
        String adm = admin();
        String plate = RecordCancellationIntegrationTest.plate();
        mvc.perform(post("/api/parking/check-in").header("Authorization", off)
                        .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1")).andExpect(status().isOk());
        mvc.perform(post("/api/parking/runaway").header("Authorization", off).param("plate", plate))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("q", plate).param("action", "VEHICLE_RUNAWAY"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].after.debt").exists());

        // login row carries the device in its after-JSON
        mvc.perform(get("/api/admin/audit").header("Authorization", adm)
                        .param("action", "LOGIN").param("actor", "tevfik").param("size", "200"))
                .andExpect(jsonPath("$.content[?(@.after.deviceId=='" + device + "')]").isNotEmpty());

        String bogus = "ghost_" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + bogus + "\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("action", "LOGIN_FAILED").param("actor", bogus))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].summary").value("Başarısız giriş denemesi"));
    }

    @Test
    void tariffDraftIsAudited() throws Exception {
        String adm = admin();
        String note = "audit-" + UUID.randomUUID().toString().substring(0, 8);
        String draft = "{\"smallFirstHour\":30,\"smallExtraHour\":20,\"largeFirstHour\":60,\"largeExtraHour\":40,"
                + "\"freeMinutes\":10,\"paidStart\":\"09:00\",\"paidEnd\":\"20:00\",\"subscriptionSmallMonthly\":2800,"
                + "\"subscriptionLargeMonthly\":3300,\"note\":\"" + note + "\"}";
        String body = mvc.perform(post("/api/admin/tariffs").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Integer id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        mvc.perform(get("/api/admin/audit").header("Authorization", adm)
                        .param("action", "TARIFF_DRAFT_CREATED").param("entityId", String.valueOf(id)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].after.note").value(note));
        // cancel the draft so other tests see no leftovers
        mvc.perform(post("/api/admin/tariffs/" + id + "/cancel").header("Authorization", adm)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").header("Authorization", adm)
                        .param("action", "TARIFF_CANCELLED").param("entityId", String.valueOf(id)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void developerActionsVisibleToAdmin() throws Exception {
        User dev = users.findByUsername("gelistirici").orElseThrow();
        String marker = "dev-marker-" + UUID.randomUUID();
        auditService.logAs(dev, AuditAction.DEV_HEALTH_VIEWED, "Developer", "health", marker, null, null);
        mvc.perform(get("/api/admin/audit").header("Authorization", admin())
                        .param("actorRole", "DEVELOPER").param("q", marker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorUsername").value("gelistirici"))
                .andExpect(jsonPath("$.content[0].action").value("DEV_HEALTH_VIEWED"));
    }

    @Test
    void actionsListAndExports() throws Exception {
        String adm = admin();
        mvc.perform(get("/api/admin/audit/actions").header("Authorization", adm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(AuditAction.values().length))
                .andExpect(jsonPath("$[?(@.action=='VEHICLE_CHECK_OUT')].label").value("Araç çıkışı"));

        byte[] csv = mvc.perform(get("/api/admin/audit/export").header("Authorization", adm).param("format", "csv"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿Zaman;Kullanıcı;"));
        assertTrue(text.contains("\r\n"));

        var res = mvc.perform(get("/api/admin/audit/export").header("Authorization", adm))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(res.getContentType().contains("spreadsheetml"));
        assertTrue(res.getHeader("Content-Disposition").contains("denetim-kaydi.xlsx"));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getContentAsByteArray()))) {
            assertEquals("Zaman", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
        mvc.perform(get("/api/admin/audit/export").header("Authorization", adm).param("format", "pdf"))
                .andExpect(status().isBadRequest());
    }
}
