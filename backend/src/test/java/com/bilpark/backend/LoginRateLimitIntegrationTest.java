package com.bilpark.backend;

import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login brute-force protection (docs/api-asama5.md "Giriş denemesi sınırı"): 429 RATE_LIMITED + Retry-After. */
@SpringBootTest(properties = {
        "bilpark.rate-limit.login.max-failures=3",
        "bilpark.rate-limit.login.lock-seconds=60,300,900",
        "bilpark.rate-limit.login.per-ip=6"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRateLimitIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private AuditLogRepository auditLogs;

    private ResultActions login(String ip, String user, String pass) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"));
    }

    private long lockRows(String user) {
        return auditLogs.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.LOGIN_LOCKED && user.equals(a.getActorUsername()))
                .count();
    }

    @Test
    void usernameIsLockedAfterNFailuresEvenForTheRightPassword() throws Exception {
        login("198.51.100.1", "admin", "wrong-1").andExpect(status().isUnauthorized());
        login("198.51.100.2", "admin", "wrong-2").andExpect(status().isUnauthorized());
        login("198.51.100.3", "admin", "wrong-3")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error").value(containsString("Çok fazla başarısız giriş denemesi")));
        // locked: the right password does not help, from any address
        login("198.51.100.4", "admin", "test-admin-pass")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertEquals(1, lockRows("admin"), "one LOGIN_LOCKED row per lock");
        assertTrue(auditLogs.findAll().stream().noneMatch(a -> String.valueOf(a.getSummary()).contains("wrong-")
                || String.valueOf(a.getAfterData()).contains("wrong-")), "no password in the audit log");
    }

    @Test
    void unknownUsernameGetsExactlyTheSameAnswers() throws Exception {
        String[] known = new String[4];
        String[] unknown = new String[4];
        for (int i = 0; i < 4; i++) {
            known[i] = answer(login("198.51.100.11", "gelistirici", "wrong-" + i));
            unknown[i] = answer(login("198.51.100.12", "nobody-here", "wrong-" + i));
        }
        assertArrayEquals(known, unknown);
        assertTrue(known[0].startsWith("401|-|"), known[0]);
        assertTrue(known[2].startsWith("429|Retry-After|"), known[2]);
        assertTrue(known[3].startsWith("429|Retry-After|"), known[3]);
    }

    /** Status, whether Retry-After is there (its seconds tick down between the two calls) and the body. */
    private static String answer(ResultActions r) throws Exception {
        var res = r.andReturn().getResponse();
        return res.getStatus() + "|" + (res.getHeader("Retry-After") != null ? "Retry-After" : "-") + "|"
                + res.getContentAsString();
    }

    @Test
    void successfulLoginResetsTheCount() throws Exception {
        for (int round = 0; round < 2; round++) {
            login("198.51.100.21", "tevfik", "wrong").andExpect(status().isUnauthorized());
            login("198.51.100.21", "tevfik", "wrong").andExpect(status().isUnauthorized());
            login("198.51.100.21", "tevfik", "test-officer-pass").andExpect(status().isOk());
        }
    }

    @Test
    void perIpCapStopsSprayingAndIgnoresASpoofedLeftmostEntry() throws Exception {
        String ip = "203.0.113.50";
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Forwarded-For", "10.0.0." + i + ", " + ip)
                            .content("{\"username\":\"spray" + i + "\",\"password\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.9.9.9, " + ip)
                        .content("{\"username\":\"spray9\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error").value(containsString("Çok fazla giriş denemesi")));
        // another client is unaffected
        login("203.0.113.51", "spray9", "x").andExpect(status().isUnauthorized());
    }
}
