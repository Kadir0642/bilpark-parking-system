package com.bilpark.backend;

import com.bilpark.backend.model.AuditLog;
import com.bilpark.backend.repository.AuditLogRepository;
import com.bilpark.backend.repository.ParkSpotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Idempotent POST /api/parking/check-in via checkInRequestId. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckInIdempotencyTest {

    @Autowired private MockMvc mvc;
    @Autowired private ParkSpotRepository spots;
    @Autowired private AuditLogRepository audits;

    private String admin() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException(body);
        return "Bearer " + m.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions checkIn(String auth, String plate, String reqId) throws Exception {
        var req = post("/api/parking/check-in").header("Authorization", auth)
                .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1");
        if (reqId != null) req.param("checkInRequestId", reqId);
        return mvc.perform(req);
    }

    private long idOf(MvcResult r) throws Exception {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(r.getResponse().getContentAsString());
        if (!m.find()) throw new IllegalStateException(r.getResponse().getContentAsString());
        return Long.parseLong(m.group(1));
    }

    private long checkInAudits(long spotId) {
        return audits.findAll().stream()
                .filter(a -> String.valueOf(spotId).equals(a.getEntityId()) && "ParkSpot".equals(a.getEntityType())
                        && a.getAction().name().equals("VEHICLE_CHECK_IN"))
                .count();
    }

    @Test
    void replayWhileActive_returnsSameSpot_noSecondSpotOrAudit() throws Exception {
        String adm = admin();
        long id1 = idOf(checkIn(adm, "34IDM001", "req-idm-0001").andExpect(status().isOk()).andReturn());
        long id2 = idOf(checkIn(adm, "34IDM001", "req-idm-0001").andExpect(status().isOk()).andReturn());
        assertEquals(id1, id2);
        assertEquals(1, spots.findAll().stream().filter(s -> "34IDM001".equals(s.getCurrentPlate())).count());
        assertEquals(1, checkInAudits(id1));
    }

    @Test
    void replayAfterCheckOut_is409DuplicateRequest() throws Exception {
        String adm = admin();
        checkIn(adm, "34IDM002", "req-idm-0002").andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34IDM002"))
                .andExpect(status().isOk());
        checkIn(adm, "34IDM002", "req-idm-0002").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"))
                .andExpect(jsonPath("$.error").value("Bu giriş daha önce işlendi."));
    }

    @Test
    void differentIdSamePlate_is409AlreadyParked() throws Exception {
        String adm = admin();
        checkIn(adm, "34IDM003", "req-idm-0003").andExpect(status().isOk());
        checkIn(adm, "34IDM003", "req-idm-0004").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARKED"));
    }

    @Test
    void invalidRequestId_is400() throws Exception {
        String adm = admin();
        for (String bad : new String[]{"short", "has space in it!", "x".repeat(65)}) {
            checkIn(adm, "34IDM005", bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST_ID"));
        }
    }

    @Test
    void withoutRequestId_behaviourUnchanged() throws Exception {
        String adm = admin();
        checkIn(adm, "34IDM006", null).andExpect(status().isOk());
        checkIn(adm, "34IDM006", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARKED"));
    }
}
