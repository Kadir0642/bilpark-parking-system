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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login brute-force protection (docs/api-asama5.md "Giriş denemesi sınırı"), production defaults except per-ip. */
@SpringBootTest(properties = {
        "bilpark.rate-limit.login.max-failures=6",
        "bilpark.rate-limit.login.lock-seconds=60,300,900",
        "bilpark.rate-limit.login.username-alert-failures=100",
        "bilpark.rate-limit.login.per-ip=10"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRateLimitIntegrationTest {

    static final String WRONG = "Kullanıcı adı veya şifre hatalı.";

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
    void sixthFailureLocksThisAddressWithCalmMessagesOnTheWay() throws Exception {
        String ip = "198.51.100.1";
        for (int i = 1; i <= 3; i++) {
            login(ip, "admin", "wrong-" + i).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(WRONG))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.remainingAttempts").doesNotExist());
        }
        login(ip, "admin", "wrong-4").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(WRONG + " 2 deneme hakkınız kaldı."))
                .andExpect(jsonPath("$.remainingAttempts").value(2));
        login(ip, "admin", "wrong-5").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(WRONG + " 1 deneme hakkınız kaldı."))
                .andExpect(jsonPath("$.remainingAttempts").value(1));
        login(ip, "admin", "wrong-6")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(60))
                .andExpect(jsonPath("$.error").value(
                        "Çok sayıda hatalı deneme yapıldı. Güvenliğiniz için 1 dakika sonra tekrar deneyebilirsiniz."));
        // locked here: the right password does not help from this address...
        login(ip, "admin", "test-admin-pass")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
        // ...but the owner on another address is not locked out
        login("198.51.100.2", "admin", "test-admin-pass").andExpect(status().isOk());

        assertEquals(1, lockRows("admin"), "one LOGIN_LOCKED row per lock");
        assertTrue(auditLogs.findAll().stream().noneMatch(a -> String.valueOf(a.getSummary()).contains("wrong-")
                || String.valueOf(a.getAfterData()).contains("wrong-")), "no password in the audit log");
    }

    @Test
    void unknownUsernameGetsExactlyTheSameAnswers() throws Exception {
        String[] known = new String[7];
        String[] unknown = new String[7];
        for (int i = 0; i < 7; i++) {
            known[i] = answer(login("198.51.100.11", "gelistirici", "wrong-" + i));
            unknown[i] = answer(login("198.51.100.12", "nobody-here", "wrong-" + i));
        }
        assertArrayEquals(known, unknown);
        assertTrue(known[0].startsWith("401|-|"), known[0]);
        assertTrue(known[4].contains("\"remainingAttempts\":1"), known[4]);
        assertTrue(known[5].startsWith("429|Retry-After|"), known[5]);
        assertTrue(known[6].startsWith("429|Retry-After|"), known[6]);
    }

    /** Status, whether Retry-After is there and the body (seconds may tick down between the two calls). */
    private static String answer(ResultActions r) throws Exception {
        var res = r.andReturn().getResponse();
        return res.getStatus() + "|" + (res.getHeader("Retry-After") != null ? "Retry-After" : "-") + "|"
                + res.getContentAsString().replaceAll("\"retryAfterSeconds\":\\d+", "\"retryAfterSeconds\":N")
                .replaceAll("\\d+ dakika", "N dakika");
    }

    @Test
    void successfulLoginResetsTheCount() throws Exception {
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                login("198.51.100.21", "tevfik", "wrong").andExpect(status().isUnauthorized());
            }
            login("198.51.100.21", "tevfik", "test-officer-pass").andExpect(status().isOk());
        }
    }

    @Test
    void perIpCapStopsSprayingAndIgnoresASpoofedLeftmostEntry() throws Exception {
        String ip = "203.0.113.50";
        for (int i = 0; i < 10; i++) {
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
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(jsonPath("$.error").value(
                        "Bu bağlantıdan kısa sürede çok sayıda giriş denemesi yapıldı. Lütfen 1 dakika sonra tekrar deneyin."));
        // another client is unaffected
        login("203.0.113.51", "spray9", "x").andExpect(status().isUnauthorized());
    }
}
