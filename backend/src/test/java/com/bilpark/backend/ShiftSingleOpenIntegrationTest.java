package com.bilpark.backend;

import com.bilpark.backend.model.Shift;
import com.bilpark.backend.repository.ShiftRepository;
import com.bilpark.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reinstall scenario: login with a new device id closes the shift left open on the old one. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShiftSingleOpenIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ShiftRepository shifts;
    @Autowired private UserRepository users;

    private String login(String user, String pass, String device) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device-Id", device)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    @Test
    void loginOnNewDeviceClosesOldDeviceShift() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String devA = "dev-old-" + tag;
        String devB = "dev-new-" + tag;
        login("tevfik", "test-officer-pass", devA);
        String adm = login("admin", "test-admin-pass", "dev-adm-" + tag);
        login("tevfik", "test-officer-pass", devB);

        var user = users.findByUsername("tevfik").orElseThrow();
        List<Shift> open = shifts.findByUserAndEndTimeIsNull(user);
        assertEquals(1, open.size());
        assertEquals(devB, open.get(0).getDeviceId());
        assertFalse(open.get(0).isAutoClosed());

        // the old shift is closed by the system and the audit trail says so
        mvc.perform(get("/api/admin/audit").header("Authorization", adm)
                        .param("action", "SHIFT_AUTO_CLOSED").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.before.deviceId=='" + devA + "')]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.after.newDeviceId=='" + devB + "')]").isNotEmpty());
    }
}
