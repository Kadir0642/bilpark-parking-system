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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Plate lookup rate limit (docs/api-asama3.md "Hız sınırı"): 429 RATE_LIMITED with a Turkish message. */
@SpringBootTest(properties = {
        "bilpark.rate-limit.plate-lookup.per-ip=3",
        "bilpark.rate-limit.plate-lookup.short-per-ip=2",
        "bilpark.rate-limit.plate-lookup.short-max-length=5",
        "bilpark.rate-limit.plate-lookup.short-global=1000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlateLookupRateLimitIntegrationTest {

    @Autowired private MockMvc mvc;

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException(body);
        return "Bearer " + m.group(1);
    }

    @Test
    void citizenDebtLookupIsLimitedPerClientIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/parking/debt").param("plate", "34ZZZ99" + i).header("X-Forwarded-For", "198.51.100.10"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/parking/debt").param("plate", "34ZZZ993").header("X-Forwarded-For", "198.51.100.10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Çok fazla plaka sorgusu")));

        // another citizen behind the same proxy is not held back
        mvc.perform(get("/api/parking/debt").param("plate", "34ZZZ993").header("X-Forwarded-For", "198.51.100.11"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shortCitizenLookupsHitTheStricterLimitFirst() throws Exception {
        mvc.perform(get("/api/parking/debt").param("plate", "AB 1").header("X-Forwarded-For", "198.51.100.20"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/parking/debt").param("plate", "AB2").header("X-Forwarded-For", "198.51.100.20"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/parking/debt").param("plate", "AB3").header("X-Forwarded-For", "198.51.100.20"))
                .andExpect(status().isTooManyRequests());
        // a full plate still has room in the per-IP limit (3)
        mvc.perform(get("/api/parking/debt").param("plate", "34ZZZ990").header("X-Forwarded-For", "198.51.100.20"))
                .andExpect(status().isNotFound());
    }

    @Test
    void developerPlateSearchIsLimitedButFiltersAreNot() throws Exception {
        String dev = login("gelistirici", "test-developer-pass1");
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/developer/audit").header("Authorization", dev)
                            .header("X-Forwarded-For", "198.51.100.30").param("q", "M1"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/developer/audit").header("Authorization", dev)
                        .header("X-Forwarded-For", "198.51.100.30").param("q", "M2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // listing without a plate search, free text and refused input are not counted
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/developer/audit").header("Authorization", dev)
                            .header("X-Forwarded-For", "198.51.100.30"))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/developer/audit").header("Authorization", dev)
                            .header("X-Forwarded-For", "198.51.100.30").param("q", "giriş"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/developer/audit").header("Authorization", dev)
                        .header("X-Forwarded-For", "198.51.100.30").param("q", "AB"))
                .andExpect(status().isBadRequest());
    }
}
