package com.bilpark.backend;

import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.ZoneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The auth user cache (AuthUserCache, 30 s TTL) must never delay a security change: every test first uses the
 * token (so the user is cached), then changes the user, then expects the very next request to see the change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthUserCacheIntegrationTest {

    private static final String PASS = "Sifre-Test-1234";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired ZoneRepository zoneRepository;

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String login(String user, String pass) throws Exception {
        return "Bearer " + json(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk())).get("token").asString();
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass"); }

    private long createOfficer(String admin, String username) throws Exception {
        Zone z = zoneRepository.findAllByOrderByStreetAscZoneNumberAsc().get(0);
        return json(mvc.perform(post("/api/admin/users").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"fullName\":\"Onbellek Test\",\"password\":\""
                                + PASS + "\",\"role\":\"OFFICER\",\"zoneId\":" + z.getId() + "}"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private static String uname() { return "c_" + UUID.randomUUID().toString().substring(0, 12); }

    @Test
    void deactivationRejectsCachedTokenImmediately() throws Exception {
        String admin = admin();
        String u = uname();
        long id = createOfficer(admin, u);
        String token = login(u, PASS);
        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk()); // cached now
        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk());

        mvc.perform(put("/api/admin/users/" + id + "/active").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isUnauthorized());

        // and back: reactivation is visible at once too
        mvc.perform(put("/api/admin/users/" + id + "/active").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void adminPasswordResetRejectsCachedTokenImmediately() throws Exception {
        String admin = admin();
        String u = uname();
        long id = createOfficer(admin, u);
        String oldToken = login(u, PASS);
        mvc.perform(get("/api/me").header("Authorization", oldToken)).andExpect(status().isOk()); // cached now

        Thread.sleep(1100); // JWT iat has second precision
        mvc.perform(post("/api/admin/users/" + id + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Yeni-Sifre-5678\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
        String newToken = login(u, "Yeni-Sifre-5678");
        mvc.perform(get("/api/me").header("Authorization", newToken)).andExpect(status().isOk());
    }

    @Test
    void ownPasswordChangeRejectsCachedOldToken() throws Exception {
        String admin = admin();
        String u = uname();
        createOfficer(admin, u);
        String oldToken = login(u, PASS);
        mvc.perform(get("/api/me").header("Authorization", oldToken)).andExpect(status().isOk()); // cached now

        Thread.sleep(1100);
        String body = mvc.perform(post("/api/me/password").header("Authorization", oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASS + "\",\"newPassword\":\"Yeni-Sifre-9876\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newToken = "Bearer " + mapper.readTree(body).get("token").asString();

        mvc.perform(get("/api/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", newToken)).andExpect(status().isOk());
    }

    @Test
    void roleChangeIsVisibleImmediately() throws Exception {
        String admin = admin();
        String u = uname();
        long id = createOfficer(admin, u);
        String token = login(u, PASS);
        mvc.perform(get("/api/admin/users").header("Authorization", token)).andExpect(status().isForbidden()); // cached

        mvc.perform(put("/api/admin/users/" + id).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/users").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void corsExposesRetryAfter() throws Exception {
        mvc.perform(get("/api/health").header("Origin", "https://example.org"))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Access-Control-Expose-Headers", hasItem(
                        org.hamcrest.Matchers.containsString("Retry-After"))));
        mvc.perform(options("/api/parking/debt").header("Origin", "https://example.org")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk());
    }
}
