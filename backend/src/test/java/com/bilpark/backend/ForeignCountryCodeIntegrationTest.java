package com.bilpark.backend;

import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.repository.AuditLogRepository;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Optional country code of a foreign plate (V11, docs/api-arac-tipleri.md "Yabancı plaka ülke kodu"):
 * check-in foreignCountryCode, 1-3 letters A-Z, only together with foreignPlate=true.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForeignCountryCodeIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ParkSpotRepository spots;
    @Autowired private ParkingRecordRepository records;
    @Autowired private AuditLogRepository audits;

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException(body);
        return "Bearer " + m.group(1);
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass"); }
    private String dev() throws Exception { return login("gelistirici", "test-developer-pass1"); }

    private ResultActions checkIn(String auth, String plate, Boolean foreign, String country, String reqId) throws Exception {
        var req = post("/api/parking/check-in").header("Authorization", auth)
                .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1");
        if (foreign != null) req.param("foreignPlate", foreign.toString());
        if (country != null) req.param("foreignCountryCode", country);
        if (reqId != null) req.param("checkInRequestId", reqId);
        return mvc.perform(req);
    }

    private List<ParkingRecord> recordsOf(String plate) {
        return records.findAll().stream().filter(r -> plate.equals(r.getLicensePlate())).toList();
    }

    @Test
    void codeIsNormalized_persisted_returned_andCopiedToTheRecord() throws Exception {
        String adm = admin();
        checkIn(adm, "M CC 1001", true, " d ", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPlate").value("MCC1001"))
                .andExpect(jsonPath("$.foreignPlate").value(true))
                .andExpect(jsonPath("$.foreignCountryCode").value("D"));
        assertEquals("D", spots.findByCurrentPlate("MCC1001").orElseThrow().getForeignCountryCode());

        // citizen page / QR: the plate is shown there, so the code is too
        mvc.perform(get("/api/parking/debt").param("plate", "MCC1001")).andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignCountryCode").value("D"));

        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "MCC1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignPlate").value(true))
                .andExpect(jsonPath("$.foreignCountryCode").value("D"));
        ParkingRecord r = recordsOf("MCC1001").get(0);
        assertEquals(ParkingStatus.PAID, r.getStatus());
        assertEquals("D", r.getForeignCountryCode());

        // the check-in audit keeps the code next to the flag
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction() == AuditAction.VEHICLE_CHECK_IN
                && a.getAfterData() != null && a.getAfterData().contains("MCC1001")
                && a.getAfterData().contains("\"foreignCountryCode\":\"D\"")));
    }

    @Test
    void codeIsOptional_emptyOrMissingGivesNull() throws Exception {
        String adm = admin();
        checkIn(adm, "CC 2002", true, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignPlate").value(true))
                .andExpect(jsonPath("$.foreignCountryCode").doesNotExist());
        checkIn(adm, "CC 2003", true, "   ", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignCountryCode").doesNotExist());
        assertNull(spots.findByCurrentPlate("CC2002").orElseThrow().getForeignCountryCode());
        assertNull(spots.findByCurrentPlate("CC2003").orElseThrow().getForeignCountryCode());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "CC2002"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "CC2003"))
                .andExpect(status().isOk());
        assertNull(recordsOf("CC2002").get(0).getForeignCountryCode());
    }

    @Test
    void invalidCode_isRejected_noSpotCreated() throws Exception {
        String adm = admin();
        for (String bad : List.of("ABCD", "N1", "1", "N L", "N-L", "Ü", "D.")) {
            checkIn(adm, "CC 3003", true, bad, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_COUNTRY_CODE"));
        }
        assertTrue(spots.findByCurrentPlate("CC3003").isEmpty());
    }

    @Test
    void turkishPlateWithCode_isRejected_blankCodeIsAccepted() throws Exception {
        String adm = admin();
        checkIn(adm, "34 CCK 404", null, "D", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COUNTRY_CODE"));
        checkIn(adm, "34 CCK 404", false, "TR", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COUNTRY_CODE"));
        assertTrue(spots.findByCurrentPlate("34CCK404").isEmpty());

        checkIn(adm, "34 CCK 404", false, "", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignPlate").value(false))
                .andExpect(jsonPath("$.foreignCountryCode").doesNotExist());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34CCK404"))
                .andExpect(status().isOk());
        assertNull(recordsOf("34CCK404").get(0).getForeignCountryCode());
    }

    @Test
    void idempotentReplay_runaway_andCancel_keepTheCode() throws Exception {
        String adm = admin();
        Number id1 = JsonPath.read(checkIn(adm, "NL 55 CCK 5", true, "nl", "req-ulke-0001").andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignCountryCode").value("NL"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // offline queue replay: same request id returns the stored spot with its code
        Number id2 = JsonPath.read(checkIn(adm, "NL 55 CCK 5", true, "nl", "req-ulke-0001").andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignCountryCode").value("NL"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertEquals(id1.longValue(), id2.longValue());

        mvc.perform(post("/api/parking/runaway").header("Authorization", adm).param("plate", "NL55CCK5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.foreignCountryCode").value("NL"));
        assertEquals("NL", recordsOf("NL55CCK5").get(0).getForeignCountryCode());

        checkIn(adm, "GB CC 66", true, "GB", null).andExpect(status().isOk());
        mvc.perform(post("/api/parking/spots/cancel").header("Authorization", adm).param("plate", "GBCC66")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test iptali\"}"))
                .andExpect(status().isOk());
        ParkingRecord cancelled = recordsOf("GBCC66").get(0);
        assertEquals(ParkingStatus.CANCELLED, cancelled.getStatus());
        assertEquals("GB", cancelled.getForeignCountryCode());

        // admin cancelled list (recordMap) carries the code
        String list = mvc.perform(get("/api/admin/records/cancelled").header("Authorization", adm).param("size", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(list, "$.content[?(@.plate == 'GBCC66')].foreignCountryCode");
        assertEquals(List.of("GB"), codes);
    }

    @Test
    void developerView_plateMasked_codeReadable() throws Exception {
        String adm = admin();
        checkIn(adm, "BG 7788 CC", true, "BG", null).andExpect(status().isOk());
        String body = mvc.perform(get("/api/developer/audit").header("Authorization", dev()).param("q", "BG7788CC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].after.plate").value("BG****CC"))
                .andExpect(jsonPath("$.content[0].after.foreignCountryCode").value("BG"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("BG7788CC"), body);
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "BG7788CC"))
                .andExpect(status().isOk());
    }
}
