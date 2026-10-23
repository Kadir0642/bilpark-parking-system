package com.bilpark.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TariffApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass"); }
    private String officer() throws Exception { return login("tevfik", "test-officer-pass"); }

    private static final String DRAFT_JSON = "{\"smallFirstHour\":30,\"smallExtraHour\":20,\"largeFirstHour\":60,"
            + "\"largeExtraHour\":40,\"freeMinutes\":10,\"paidStart\":\"09:00\",\"paidEnd\":\"20:00\","
            + "\"subscriptionSmallMonthly\":2800,\"subscriptionLargeMonthly\":3300,\"note\":\"it\"}";

    @Test
    void currentTariffIsPublic() throws Exception {
        mvc.perform(get("/api/tariff/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.small.firstHour").value(25.0))
                .andExpect(jsonPath("$.large.extraHour").value(30.0))
                .andExpect(jsonPath("$.freeMinutes").value(5))
                .andExpect(jsonPath("$.paidStart").value("08:00"))
                .andExpect(jsonPath("$.paidEnd").value("22:00"))
                .andExpect(jsonPath("$.subscription.small").value(2500.0));
    }

    @Test
    void officerCannotUseAdminTariffs() throws Exception {
        mvc.perform(get("/api/admin/tariffs").header("Authorization", officer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/tariffs")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidDraft_400() throws Exception {
        mvc.perform(post("/api/admin/tariffs").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRAFT_JSON.replace("\"freeMinutes\":10", "\"freeMinutes\":500")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARIFF"));
    }

    @Test
    void draftPreviewScheduleFlow() throws Exception {
        String admin = admin();
        String created = mvc.perform(post("/api/admin/tariffs").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(created);
        if (!m.find()) throw new IllegalStateException(created);
        String id = m.group(1);

        mvc.perform(get("/api/admin/tariffs").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists());

        mvc.perform(post("/api/admin/tariffs/" + id + "/preview").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(20))
                .andExpect(jsonPath("$.rows[0].current.fee").exists())
                .andExpect(jsonPath("$.rows[0].proposed.segments").isArray());

        String when = "\"effectiveFrom\":\"2099-01-01T00:00:00\"";
        mvc.perform(post("/api/admin/tariffs/" + id + "/schedule").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + when + ",\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));

        mvc.perform(post("/api/admin/tariffs/" + id + "/schedule").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + when + ",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        mvc.perform(get("/api/tariff/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.small.firstHour").value(25.0))
                .andExpect(jsonPath("$.upcoming.small.firstHour").value(30.0))
                .andExpect(jsonPath("$.upcoming.effectiveFrom").value("2099-01-01T00:00:00"));

        mvc.perform(post("/api/admin/tariffs/" + id + "/cancel").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(get("/api/tariff/current"))
                .andExpect(jsonPath("$.upcoming").value((Object) null));
    }

    @Test
    void debtHasSegmentsAndFeePreviewRespectsZones() throws Exception {
        String admin = admin();
        String officer = officer();
        // own zone (1) and another zone (2) of the same street
        mvc.perform(post("/api/parking/check-in").header("Authorization", officer)
                        .param("plate", "34TRF01").param("street", "TEVFIK_BEY").param("zoneId", "1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-in").header("Authorization", admin)
                        .param("plate", "34TRG01").param("street", "TEVFIK_BEY").param("zoneId", "2"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/parking/debt").param("plate", "34TRF01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments").isArray())
                .andExpect(jsonPath("$.paidMinutes").exists())
                .andExpect(jsonPath("$.tariffId").exists())
                .andExpect(jsonPath("$.fee").exists());

        mvc.perform(get("/api/parking/fee-preview").header("Authorization", officer).param("plate", "34 trf 01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("SMALL"))
                .andExpect(jsonPath("$.zoneId").value(1));

        mvc.perform(get("/api/parking/fee-preview").header("Authorization", officer).param("plate", "34TRG01"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/parking/fee-preview").param("plate", "34TRF01"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/parking/runaway").header("Authorization", officer).param("plate", "34TRF01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments").isArray())
                .andExpect(jsonPath("$.debt").exists());
        mvc.perform(post("/api/parking/check-out").header("Authorization", admin).param("plate", "34TRG01"))
                .andExpect(status().isOk());
    }
}
