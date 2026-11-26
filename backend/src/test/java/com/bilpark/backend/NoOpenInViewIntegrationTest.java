package com.bilpark.backend;

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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No open-in-view: every endpoint that serializes an entity with a lazy association (record -> zone,
 * shift -> user) must still answer 200 with the association filled, not a LazyInitializationException (500).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoOpenInViewIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private String login(String user, String pass, String deviceId) throws Exception {
        var req = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}");
        if (deviceId != null) req.header("X-Device-Id", deviceId);
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    @Test
    void recordAndShiftListsSerializeLazyAssociations() throws Exception {
        String adm = login("admin", "test-admin-pass", null);
        String off = login("tevfik", "test-officer-pass", "dev-osiv");

        mvc.perform(post("/api/parking/check-in").header("Authorization", off).header("X-Device-Id", "dev-osiv")
                        .param("plate", "34OSV001").param("street", "TEVFIK_BEY"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", off).header("X-Device-Id", "dev-osiv")
                        .param("plate", "34OSV001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zone.zoneName").isNotEmpty());
        mvc.perform(post("/api/parking/check-in").header("Authorization", off).header("X-Device-Id", "dev-osiv")
                        .param("plate", "34OSV002").param("street", "TEVFIK_BEY"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/runaway").header("Authorization", off).header("X-Device-Id", "dev-osiv")
                        .param("plate", "34OSV002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.zone.zoneName").isNotEmpty());

        // record lists: zone comes from the query (entity graph)
        mvc.perform(get("/api/parking/history").header("Authorization", off).param("street", "TEVFIK_BEY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.licensePlate=='34OSV001')].zone.zoneName").isNotEmpty());
        mvc.perform(get("/api/parking/history/search").header("Authorization", off).param("plate", "34OSV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.licensePlate=='34OSV002')].zone.zoneName").isNotEmpty());
        mvc.perform(get("/api/parking/bi/last-records").header("Authorization", adm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.licensePlate=='34OSV001')].zone.zoneName").isNotEmpty());
        String today = LocalDate.now().toString();
        mvc.perform(get("/api/parking/bi/last-records").header("Authorization", adm)
                        .param("start", today).param("end", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.licensePlate=='34OSV001')].zone.zoneName").isNotEmpty());

        // shift lists: shift -> user is lazy
        mvc.perform(get("/api/parking/bi/shifts").header("Authorization", adm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.deviceId=='dev-osiv')].username", hasItem("tevfik")));
        mvc.perform(get("/api/admin/shifts").header("Authorization", adm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.deviceId=='dev-osiv')].userId").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.deviceId=='dev-osiv')].username", hasItem("tevfik")));

        // spot lists, tariff, subscriptions, users and reports still serialize
        mvc.perform(get("/api/parking/spots").header("Authorization", adm)).andExpect(status().isOk());
        mvc.perform(get("/api/parking/filter").header("Authorization", off).param("street", "TEVFIK_BEY"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tariff/current")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").header("Authorization", adm)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/reports/period/summary").header("Authorization", adm)
                .param("from", today).param("to", today)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/reports/records").header("Authorization", adm)
                .param("from", today).param("to", today)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/reports/personnel/summary").header("Authorization", adm)
                .param("month", today.substring(0, 7))).andExpect(status().isOk());
    }
}
