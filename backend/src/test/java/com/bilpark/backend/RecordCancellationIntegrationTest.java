package com.bilpark.backend;

import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.PaymentMethod;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.bilpark.backend.repository.SyncEventRepository;
import com.bilpark.backend.repository.ZoneRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Record cancellation (soft delete): admin cancel of closed records, officer cancel of a wrong entry, list. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecordCancellationIntegrationTest {

    private static final Random RND = new Random();

    @Autowired private MockMvc mvc;
    @Autowired private ParkingRecordRepository records;
    @Autowired private ParkSpotRepository spots;
    @Autowired private ZoneRepository zones;
    @Autowired private SyncEventRepository syncEvents;

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass"); }
    private String officer() throws Exception { return login("tevfik", "test-officer-pass"); }

    /** Unique valid plate: 34 + 3 letters + 3 digits. */
    static String plate() {
        StringBuilder sb = new StringBuilder("34");
        // no 'I': H2's LOWER() follows the JVM default locale (tr-TR on a Turkish OS) and would turn it into a dotless i
        for (int i = 0; i < 3; i++) sb.append("ABCDEFGHJKLMNOPRSTUVYZ".charAt(RND.nextInt(22)));
        for (int i = 0; i < 3; i++) sb.append(RND.nextInt(10));
        return sb.toString();
    }

    private ParkingRecord closed(String plate, ParkingStatus st, double fee) {
        ParkingRecord r = new ParkingRecord(plate, StreetLocation.TEVFIK_BEY, zones.findById(1L).orElseThrow(), "Merkez", "Bilecik",
                LocalDateTime.now().minusHours(2));
        r.setExitTime(LocalDateTime.now());
        r.setFee(fee);
        r.setStatus(st);
        r.setDebtPaid(st == ParkingStatus.PAID);
        r.setPaymentMethod(st == ParkingStatus.PAID ? PaymentMethod.CASH : null);
        return records.save(r);
    }

    private static final String REASON = "{\"reason\":\"Yanlış plaka girildi\"}";

    @Test
    void adminCancelsPaidRecord_leavesRevenue_rowKept() throws Exception {
        String adm = admin();
        ParkingRecord r = closed(plate(), ParkingStatus.PAID, 40.0);
        double before = Double.parseDouble(mvc.perform(get("/api/parking/income").header("Authorization", adm))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/api/admin/records/" + r.getId() + "/cancel").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.previousStatus").value("PAID"))
                .andExpect(jsonPath("$.cancelledBy").value("admin"))
                .andExpect(jsonPath("$.cancelReason").value("Yanlış plaka girildi"))
                .andExpect(jsonPath("$.fee").value(40.0));

        double after = Double.parseDouble(mvc.perform(get("/api/parking/income").header("Authorization", adm))
                .andReturn().getResponse().getContentAsString());
        assertEquals(40.0, before - after, 0.001);

        ParkingRecord stored = records.findById(r.getId()).orElseThrow();
        assertEquals(ParkingStatus.CANCELLED, stored.getStatus());
        assertEquals(ParkingStatus.PAID, stored.getCancelledFromStatus());
        assertEquals(40.0, stored.getFee(), 0.001);
        assertNotNull(stored.getCancelledAt());

        // a second cancel is 409
        mvc.perform(post("/api/admin/records/" + r.getId() + "/cancel").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));

        // listed with all fields
        mvc.perform(get("/api/admin/records/cancelled").header("Authorization", adm)
                        .param("from", LocalDateTime.now().toLocalDate().toString())
                        .param("to", LocalDateTime.now().toLocalDate().toString()).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + r.getId() + ")].previousStatus").value("PAID"))
                .andExpect(jsonPath("$.content[?(@.id==" + r.getId() + ")].cancelReason").value("Yanlış plaka girildi"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(200));
    }

    @Test
    void cancelledRunaway_leavesUnpaidDebt() throws Exception {
        String adm = admin();
        String p = plate();
        ParkingRecord r = closed(p, ParkingStatus.RUNAWAY, 30.0);
        // the debt shows when the plate checks in again
        mvc.perform(post("/api/admin/records/" + r.getId() + "/cancel").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousStatus").value("RUNAWAY"));
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", p).param("street", "TEVFIK_BEY").param("zoneId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accumulatedDebt").value(0.0));
    }

    @Test
    void reasonValidation_andStateChecks() throws Exception {
        String adm = admin();
        ParkingRecord r = closed(plate(), ParkingStatus.PAID, 10.0);
        for (String body : new String[]{"{}", "{\"reason\":\"  ab \"}", "{\"reason\":\"" + "x".repeat(501) + "\"}"}) {
            mvc.perform(post("/api/admin/records/" + r.getId() + "/cancel").header("Authorization", adm)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error").value("İptal gerekçesi zorunludur (en az 5 karakter)."));
        }
        mvc.perform(post("/api/admin/records/999999999/cancel").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isNotFound());
        // an ACTIVE-from-spot record is not what the admin cancel takes; here a record with status ACTIVE is 400
        ParkingRecord odd = closed(plate(), ParkingStatus.ACTIVE, 0.0);
        mvc.perform(post("/api/admin/records/" + odd.getId() + "/cancel").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void officerCannotUseAdminEndpoints() throws Exception {
        String off = officer();
        ParkingRecord r = closed(plate(), ParkingStatus.PAID, 10.0);
        mvc.perform(post("/api/admin/records/" + r.getId() + "/cancel").header("Authorization", off)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/records/cancelled").header("Authorization", off))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/records/cancelled")).andExpect(status().isUnauthorized());
        assertEquals(ParkingStatus.PAID, records.findById(r.getId()).orElseThrow().getStatus());
    }

    @Test
    void officerCancelsWrongEntry_inOwnZone() throws Exception {
        String adm = admin();
        String off = officer();
        String p = plate();
        mvc.perform(post("/api/parking/check-in").header("Authorization", off)
                        .param("plate", p).param("street", "TEVFIK_BEY").param("zoneId", "1"))
                .andExpect(status().isOk());
        long eventsBefore = syncEvents.count();

        mvc.perform(post("/api/parking/spots/cancel").header("Authorization", off).header("X-Device-Id", "dev-cancel-1")
                        .param("plate", p).contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.fee").value(0.0))
                .andExpect(jsonPath("$.collectedBy").value("tevfik"));

        assertTrue(spots.findByCurrentPlate(p).isEmpty());
        ParkingRecord rec = records.findTopByLicensePlateOrderByExitTimeDesc(p).orElseThrow();
        assertEquals(ParkingStatus.CANCELLED, rec.getStatus());
        assertEquals("dev-cancel-1", rec.getDeviceId());
        assertTrue(syncEvents.count() > eventsBefore);

        // the plate can check in again, the cancelled row is not a debt
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", p).param("street", "TEVFIK_BEY").param("zoneId", "1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", p))
                .andExpect(status().isOk());

        // cancelling a vehicle that left a moment ago: 409 with the "İptal"/closed wording, never silent success
        String q = plate();
        mvc.perform(post("/api/parking/check-in").header("Authorization", off)
                        .param("plate", q).param("street", "TEVFIK_BEY").param("zoneId", "1")).andExpect(status().isOk());
        mvc.perform(post("/api/parking/spots/cancel").header("Authorization", off).param("plate", q)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON)).andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", off).param("plate", q))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PROCESSED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("İptal")));
        mvc.perform(post("/api/parking/spots/cancel").header("Authorization", off).param("plate", plate())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isNotFound());
    }

    @Test
    void officerCannotCancelOtherZone_adminCan() throws Exception {
        String adm = admin();
        String off = officer();
        String p = plate();
        // zone 2 of the same street: admin checks in, the zone-1 officer must be refused
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", p).param("street", "TEVFIK_BEY").param("zoneId", "2"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/spots/cancel").header("Authorization", off).param("plate", p)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden());
        assertTrue(spots.findByCurrentPlate(p).isPresent());

        String body = mvc.perform(post("/api/parking/spots/cancel").header("Authorization", adm).param("plate", p)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("CANCELLED", JsonPath.read(body, "$.status"));
        assertEquals("admin", JsonPath.read(body, "$.cancelledBy"));
    }

    @Test
    void cancelledListPaging() throws Exception {
        String adm = admin();
        for (int i = 0; i < 3; i++) {
            ParkingRecord r = closed(plate(), ParkingStatus.PAID, 5.0);
            mvc.perform(post("/api/admin/records/" + r.getId() + "/cancel").header("Authorization", adm)
                            .contentType(MediaType.APPLICATION_JSON).content(REASON)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/admin/records/cancelled").header("Authorization", adm).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        mvc.perform(get("/api/admin/records/cancelled").header("Authorization", adm).param("from", "bad"))
                .andExpect(status().isBadRequest());
    }
}
