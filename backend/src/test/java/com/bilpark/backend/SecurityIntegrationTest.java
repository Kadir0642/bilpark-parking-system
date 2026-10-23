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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

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

    private static final String SUB_JSON =
            "{\"licensePlate\":\"34SUB001\",\"vehicleType\":\"SMALL\",\"ownerName\":\"Test\",\"ownerPhone\":\"5550000000\"}";

    @Test
    void anonymousPay_is401() throws Exception {
        mvc.perform(post("/api/parking/pay").param("plate", "34PAY001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousSubscriptionCreate_is401() throws Exception {
        mvc.perform(post("/api/subscription/create").contentType(MediaType.APPLICATION_JSON).content(SUB_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void officerSubscriptionCreate_is403() throws Exception {
        mvc.perform(post("/api/subscription/create").header("Authorization", officer())
                        .contentType(MediaType.APPLICATION_JSON).content(SUB_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidToken_is401() throws Exception {
        mvc.perform(post("/api/parking/check-in").header("Authorization", "Bearer not.a.token")
                        .param("plate", "34BAD001").param("street", "TEVFIK_BEY"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void officerCheckIn_otherZone_is403() throws Exception {
        // zone 2 = TEVFIK_BEY bolum 2 (officer is in zone 1)
        mvc.perform(post("/api/parking/check-in").header("Authorization", officer())
                        .param("plate", "34OZ2001").param("street", "TEVFIK_BEY").param("zoneId", "2"))
                .andExpect(status().isForbidden());
    }

    @Test
    void officerCheckIn_otherStreet_is403() throws Exception {
        mvc.perform(post("/api/parking/check-in").header("Authorization", officer())
                        .param("plate", "34OST001").param("street", "CUMHURIYET").param("zoneId", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void officerCheckIn_ownZone_is200() throws Exception {
        mvc.perform(post("/api/parking/check-in").header("Authorization", officer())
                        .param("plate", "34OWN001").param("street", "TEVFIK_BEY").param("zoneId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void officerCheckOutAndRunaway_otherZoneVehicle_is403() throws Exception {
        String adm = admin();
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                        .param("plate", "34ADM001").param("street", "TEVFIK_BEY").param("zoneId", "2"))
                .andExpect(status().isOk());

        String off = officer();
        mvc.perform(post("/api/parking/check-out").header("Authorization", off).param("plate", "34ADM001"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/parking/runaway").header("Authorization", off).param("plate", "34ADM001"))
                .andExpect(status().isForbidden());

        // admin can still check it out
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34ADM001"))
                .andExpect(status().isOk());
    }
}
