package com.bilpark.backend;

import com.bilpark.backend.repository.ParkingRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end API behaviour on H2: error format, conflicts, payment method, shifts, archive, renewal. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ParkingFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ParkingRecordRepository recordRepository;

    private String login(String user, String pass, String deviceId) throws Exception {
        var req = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}");
        if (deviceId != null) req.header("X-Device-Id", deviceId);
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass", null); }
    private String officer(String device) throws Exception { return login("tevfik", "test-officer-pass", device); }

    @Test
    void unauthenticated_is401WithJsonCode() throws Exception {
        mvc.perform(post("/api/parking/check-out").param("plate", "34ABC123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void officerOnAdminEndpoint_is403WithJsonCode() throws Exception {
        mvc.perform(get("/api/parking/bi/shifts").header("Authorization", officer("dev-403")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void invalidPlate_is400() throws Exception {
        mvc.perform(post("/api/parking/check-in").header("Authorization", admin())
                        .param("plate", "ABC").param("street", "TEVFIK_BEY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PLATE"));
    }

    @Test
    void duplicateCheckIn_is409AlreadyParked() throws Exception {
        String adm = admin();
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", "34DUP001").param("street", "TEVFIK_BEY").param("zoneId", "1"))
                .andExpect(status().isOk());
        // same plate, different formatting, different zone: still the same active vehicle
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", "34 dup-001").param("street", "TEVFIK_BEY").param("zoneId", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARKED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("zaten sistemde aktif")));
    }

    @Test
    void secondCheckOut_is409AlreadyProcessed_andUnknownPlateIs404() throws Exception {
        String adm = admin();
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", "34TWO001").param("street", "TEVFIK_BEY").param("zoneId", "1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34TWO001"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34TWO001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PROCESSED"));
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34NOP001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VEHICLE_NOT_FOUND"));
    }

    @Test
    void checkOutWithPos_recordsMethodCollectorAndDevice() throws Exception {
        String off = officer("dev-pos");
        mvc.perform(post("/api/parking/check-in").header("Authorization", off).header("X-Device-Id", "dev-pos")
                        .param("plate", "34POS001").param("street", "TEVFIK_BEY"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", off).header("X-Device-Id", "dev-pos")
                        .param("plate", "34POS001").param("paymentMethod", "pos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("POS"))
                .andExpect(jsonPath("$.collectedByUsername").value("tevfik"))
                .andExpect(jsonPath("$.deviceId").value("dev-pos"));
    }

    @Test
    void badPaymentMethods_are400() throws Exception {
        String adm = admin();
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm)
                        .param("plate", "34BPM001").param("paymentMethod", "ONLINE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_METHOD"));
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm)
                        .param("plate", "34BPM001").param("paymentMethod", "BITCOIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_METHOD"));
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", "34BPM001").param("street", "TEVFIK_BEY"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm)
                        .param("plate", "34BPM001").param("paymentMethod", "SUBSCRIPTION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_SUBSCRIPTION"));
    }

    @Test
    void officerLoginOpensShiftVisibleToAdmin() throws Exception {
        officer("dev-shift-7");
        mvc.perform(get("/api/parking/bi/shifts").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.deviceId=='dev-shift-7')].username").value("tevfik"))
                .andExpect(jsonPath("$[?(@.deviceId=='dev-shift-7')].shiftId").isNotEmpty());
    }

    @Test
    void logoutClosesOnlyThatDevicesShift() throws Exception {
        String a = officer("dev-lo-A");
        officer("dev-lo-B");
        mvc.perform(post("/api/auth/logout").header("Authorization", a).header("X-Device-Id", "dev-lo-A"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/parking/bi/shifts").header("Authorization", admin()))
                .andExpect(jsonPath("$[?(@.deviceId=='dev-lo-A')]").isEmpty())
                .andExpect(jsonPath("$[?(@.deviceId=='dev-lo-B')]").isNotEmpty());
    }

    @Test
    void fiscalYearArchive_marksArchivedAndKeepsRecords() throws Exception {
        String adm = admin();
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", "34FYA001").param("street", "TEVFIK_BEY"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34FYA001"))
                .andExpect(status().isOk());
        long before = recordRepository.count();
        assertTrue(before > 0);

        LocalDate today = LocalDate.now();
        String created = mvc.perform(post("/api/admin/fiscal-years/create").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"" + today.withDayOfYear(1) + "\",\"endDate\":\"" + today.plusYears(1)
                                + "\",\"label\":\"Test Dönemi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false))
                .andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(created);
        assertTrue(m.find());
        String id = m.group(1);

        mvc.perform(post("/api/admin/fiscal-years/" + id + "/archive").header("Authorization", adm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        assertEquals(before, recordRepository.count());

        mvc.perform(post("/api/admin/fiscal-years/" + id + "/archive").header("Authorization", adm))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ARCHIVED"));
        mvc.perform(post("/api/admin/fiscal-years/999999/archive").header("Authorization", adm))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void fiscalYearCreate_startAfterEnd_is400() throws Exception {
        mvc.perform(post("/api/admin/fiscal-years/create").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2030-02-01\",\"endDate\":\"2030-01-01\",\"label\":\"Ters\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void subscriptionCreateCancelCreateAgain_renewalWorks() throws Exception {
        String adm = admin();
        String json = "{\"licensePlate\":\"34 rnw 001\",\"vehicleType\":\"SMALL\",\"ownerName\":\"T\",\"ownerPhone\":\"5\"}";
        mvc.perform(post("/api/subscription/create").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licensePlate").value("34RNW001"));
        mvc.perform(post("/api/subscription/create").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        mvc.perform(post("/api/subscription/cancel").header("Authorization", adm).param("plate", "34RNW001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/subscription/create").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/subscription/check").header("Authorization", adm).param("plate", "34-RNW-001"))
                .andExpect(jsonPath("$.hasSubscription").value(true));
    }

    @Test
    void subscribedVehicleCheckOut_forcesSubscriptionMethod() throws Exception {
        String adm = admin();
        mvc.perform(post("/api/subscription/create").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licensePlate\":\"34SBX001\",\"vehicleType\":\"SMALL\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", "34SBX001").param("street", "TEVFIK_BEY"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm)
                        .param("plate", "34SBX001").param("paymentMethod", "CASH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("SUBSCRIPTION"))
                .andExpect(jsonPath("$.fee").value(0.0));
    }

    @Test
    void historySearchNotFound_usesUnifiedBody() throws Exception {
        mvc.perform(get("/api/parking/history/search").header("Authorization", admin()).param("plate", "99ZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error").value("Bu plaka için kayıt bulunamadı."));
    }

    @Test
    void biEndpointsWork() throws Exception {
        String adm = admin();
        mvc.perform(get("/api/parking/bi/income-by-type").header("Authorization", adm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Otomobil").isNumber())
                .andExpect(jsonPath("$.Kamyonet").isNumber());
        mvc.perform(get("/api/parking/bi/status-count").header("Authorization", adm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['Ödenen']").isNumber())
                .andExpect(jsonPath("$.['Kaçan']").isNumber());
        LocalDate today = LocalDate.now();
        mvc.perform(get("/api/parking/bi/last-records").header("Authorization", adm)
                        .param("start", today.toString()).param("end", today.toString()))
                .andExpect(status().isOk());
    }
}
