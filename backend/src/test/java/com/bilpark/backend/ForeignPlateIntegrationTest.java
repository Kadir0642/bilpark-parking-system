package com.bilpark.backend;

import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Foreign / special plates (V10, docs/api-arac-tipleri.md "Yabancı / özel plaka"): check-in foreignPlate=true. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForeignPlateIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ParkSpotRepository spots;
    @Autowired private ParkingRecordRepository records;

    private String admin() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException(body);
        return "Bearer " + m.group(1);
    }

    private ResultActions checkIn(String auth, String plate, Boolean foreign, String reqId) throws Exception {
        var req = post("/api/parking/check-in").header("Authorization", auth)
                .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1");
        if (foreign != null) req.param("foreignPlate", foreign.toString());
        if (reqId != null) req.param("checkInRequestId", reqId);
        return mvc.perform(req);
    }

    private List<ParkingRecord> recordsOf(String plate) {
        return records.findAll().stream().filter(r -> plate.equals(r.getLicensePlate())).toList();
    }

    @Test
    void foreignCheckIn_checkOut_recordKeepsFlag() throws Exception {
        String adm = admin();
        checkIn(adm, "m ab-1234", true, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPlate").value("MAB1234"))
                .andExpect(jsonPath("$.foreignPlate").value(true));
        assertTrue(spots.findByCurrentPlate("MAB1234").orElseThrow().isForeignPlate());

        // citizen page / QR and the officer lookups find the foreign plate
        mvc.perform(get("/api/parking/debt").param("plate", "MAB 1234")).andExpect(status().isOk())
                .andExpect(jsonPath("$.plate").value("MAB1234"))
                .andExpect(jsonPath("$.foreignPlate").value(true));
        mvc.perform(get("/api/parking/fee-preview").header("Authorization", adm).param("plate", "M-AB-1234"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "M AB 1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licensePlate").value("MAB1234"))
                .andExpect(jsonPath("$.foreignPlate").value(true));
        List<ParkingRecord> rs = recordsOf("MAB1234");
        assertEquals(1, rs.size());
        assertTrue(rs.get(0).isForeignPlate());
        assertEquals(ParkingStatus.PAID, rs.get(0).getStatus());
    }

    @Test
    void turkishCheckIn_flagFalse() throws Exception {
        String adm = admin();
        checkIn(adm, "34 FRN 101", null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignPlate").value(false));
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34FRN101"))
                .andExpect(status().isOk());
        assertFalse(recordsOf("34FRN101").get(0).isForeignPlate());
    }

    @Test
    void foreignPlateWithoutFlag_isRejected() throws Exception {
        String adm = admin();
        for (String plate : List.of("MAB1235", "CD 1234", "12-ABC-3")) {
            checkIn(adm, plate, null, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PLATE"));
            checkIn(adm, plate, false, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PLATE"));
        }
    }

    @Test
    void foreignFlag_badCharactersOrLength_isRejected() throws Exception {
        String adm = admin();
        for (String plate : List.of("A", "ABCDEFG1234567", "M.AB.1234", "AB/12", "ÇAB123", "AB_12", "MAB+1")) {
            checkIn(adm, plate, true, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PLATE"));
        }
        assertTrue(spots.findByCurrentPlate("ABCDEFG1234567").isEmpty());
    }

    @Test
    void duplicateActiveForeignPlate_is409_idempotentReplayReturnsSameSpot() throws Exception {
        String adm = admin();
        Number id1 = JsonPath.read(checkIn(adm, "NL12ABC3", true, "req-frn-0001").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // offline queue replay: same request id, same spot, no second row
        Number id2 = JsonPath.read(checkIn(adm, "NL12ABC3", true, "req-frn-0001").andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignPlate").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertEquals(id1.longValue(), id2.longValue());
        assertEquals(1, spots.findAll().stream().filter(s -> "NL12ABC3".equals(s.getCurrentPlate())).count());

        // another entry of the same active plate (other spelling, other request id) is a conflict
        checkIn(adm, "nl 12-abc 3", true, "req-frn-0002").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARKED"));
        checkIn(adm, "NL12ABC3", true, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARKED"));

        // cancel keeps the flag on the CANCELLED record
        mvc.perform(post("/api/parking/spots/cancel").header("Authorization", adm).param("plate", "NL12ABC3")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test iptali\"}"))
                .andExpect(status().isOk());
        ParkingRecord cancelled = recordsOf("NL12ABC3").get(0);
        assertEquals(ParkingStatus.CANCELLED, cancelled.getStatus());
        assertTrue(cancelled.isForeignPlate());
    }

    @Test
    void runaway_keepsFlag_debtShownOnNextVisit() throws Exception {
        String adm = admin();
        checkIn(adm, "CD 9876", true, null).andExpect(status().isOk());
        mvc.perform(post("/api/parking/runaway").header("Authorization", adm).param("plate", "CD9876"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.foreignPlate").value(true));
        ParkingRecord runaway = recordsOf("CD9876").get(0);
        assertEquals(ParkingStatus.RUNAWAY, runaway.getStatus());
        assertTrue(runaway.isForeignPlate());

        // give the runaway a known debt, then the next visit shows it
        runaway.setFee(42.0);
        records.save(runaway);
        checkIn(adm, "cd-9876", true, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.accumulatedDebt").value(42.0));
        mvc.perform(get("/api/parking/debt").param("plate", "CD9876")).andExpect(status().isOk())
                .andExpect(jsonPath("$.foreignPlate").value(true));
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "CD9876"))
                .andExpect(status().isOk());
    }

    @Test
    void debtLookup_invalidPlateStillRejected() throws Exception {
        mvc.perform(get("/api/parking/debt").param("plate", "A")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLATE"));
        mvc.perform(get("/api/parking/debt").param("plate", "ZZ9999ZZ")).andExpect(status().isNotFound());
    }
}
