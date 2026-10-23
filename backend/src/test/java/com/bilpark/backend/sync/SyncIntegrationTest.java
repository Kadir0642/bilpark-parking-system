package com.bilpark.backend.sync;

import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.repository.ZoneRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyncIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ChangePublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper mapper;
    @Autowired DataSource dataSource;
    @Autowired UserRepository userRepository;
    @Autowired ZoneRepository zoneRepository;
    @Autowired PasswordEncoder passwordEncoder;

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

    private JsonNode getJson(String url, String auth) throws Exception {
        String body = mvc.perform(get(url).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private long latest() throws Exception {
        return getJson("/api/sync", admin()).get("version").asLong();
    }

    @Test
    void sync_anonymous_is401() throws Exception {
        mvc.perform(get("/api/sync")).andExpect(status().isUnauthorized());
    }

    @Test
    void sync_withoutSince_returnsEmptyEventsAndVersion() throws Exception {
        publisher.globalEvent(SyncEventType.TARIFF_UPDATED, Map.of("x", 1));
        JsonNode r = getJson("/api/sync", officer());
        assertTrue(r.get("version").asLong() > 0);
        assertEquals(0, r.get("events").size());
        assertFalse(r.get("hasMore").asBoolean());
        assertFalse(r.get("resetRequired").asBoolean());
    }

    @Test
    void sync_officerSeesOnlyOwnZoneAndGlobal_inCommitOrder() throws Exception {
        long before = latest();
        tx.executeWithoutResult(s -> {
            publisher.zoneEvent(SyncEventType.VEHICLE_ENTERED, 1L, StreetLocation.TEVFIK_BEY, Map.of("plate", "34A1"));
            publisher.zoneEvent(SyncEventType.VEHICLE_ENTERED, 2L, StreetLocation.TEVFIK_BEY, Map.of("plate", "34B2"));
            publisher.globalEvent(SyncEventType.TARIFF_UPDATED, Map.of("v", 3));
        });
        JsonNode off = getJson("/api/sync?since=" + before, officer());
        assertEquals(2, off.get("events").size());
        assertEquals("VEHICLE_ENTERED", off.get("events").get(0).get("type").asString());
        assertEquals("34A1", off.get("events").get(0).get("payload").get("plate").asString());
        assertEquals("ZONE", off.get("events").get(0).get("scope").asString());
        assertEquals(1, off.get("events").get(0).get("zoneId").asInt());
        assertEquals("TARIFF_UPDATED", off.get("events").get(1).get("type").asString());
        assertFalse(off.get("resetRequired").asBoolean());

        JsonNode adm = getJson("/api/sync?since=" + before, admin());
        assertEquals(3, adm.get("events").size());
        assertEquals(off.get("version").asLong(), adm.get("version").asLong());
    }

    @Test
    void sync_sinceAfterLatest_requiresReset() throws Exception {
        JsonNode r = getJson("/api/sync?since=" + (latest() + 1000), officer());
        assertTrue(r.get("resetRequired").asBoolean());
        assertEquals(0, r.get("events").size());
    }

    @Test
    void events_withoutAuth_is401() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void events_withBearer_startsAsyncStream() throws Exception {
        mvc.perform(get("/api/events").header("Authorization", officer()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
    }

    @Test
    void ticket_isSingleUse() throws Exception {
        String body = mvc.perform(post("/api/events/ticket").header("Authorization", officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(60))
                .andReturn().getResponse().getContentAsString();
        String ticket = mapper.readTree(body).get("ticket").asString();
        assertTrue(ticket.length() >= 43);

        mvc.perform(get("/api/events").param("ticket", ticket).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
        mvc.perform(get("/api/events").param("ticket", ticket).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events").param("ticket", "bogus")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/events/ticket")).andExpect(status().isUnauthorized());
    }

    @Test
    void openStream_holdsNoDatabaseConnection() throws Exception {
        String auth = officer();
        mvc.perform(get("/api/events").header("Authorization", auth).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        long deadline = System.currentTimeMillis() + 3000;
        while (hikari.getHikariPoolMXBean().getActiveConnections() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(0, hikari.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void deactivation_blocksTokenAndLogin_andRules() throws Exception {
        String name = "pasif" + UUID.randomUUID().toString().substring(0, 8);
        User u = new User(name, passwordEncoder.encode("pw-" + name), Role.OFFICER, zoneRepository.findById(1L).orElseThrow());
        u = userRepository.save(u);
        String loginBody = "{\"username\":\"" + name + "\",\"password\":\"pw-" + name + "\"}";

        String token = login(name, "pw-" + name);
        mvc.perform(get("/api/sync").header("Authorization", token)).andExpect(status().isOk());

        // officer cannot use the admin API
        mvc.perform(get("/api/admin/users").header("Authorization", token)).andExpect(status().isForbidden());

        String adm = admin();
        JsonNode users = getJson("/api/admin/users", adm);
        boolean found = false;
        for (JsonNode n : users) {
            if (name.equals(n.get("username").asString())) {
                found = true;
                assertTrue(n.get("active").asBoolean());
                assertEquals(1, n.get("zoneId").asInt());
            }
        }
        assertTrue(found);

        long before = latest();
        mvc.perform(put("/api/admin/users/" + u.getId() + "/active").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/api/sync").header("Authorization", token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isUnauthorized());

        JsonNode events = getJson("/api/sync?since=" + before, adm).get("events");
        assertEquals("USER_DEACTIVATED", events.get(events.size() - 1).get("type").asString());

        // reactivation works
        mvc.perform(put("/api/admin/users/" + u.getId() + "/active").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk());
    }

    @Test
    void adminCannotDeactivateSelf() throws Exception {
        Long adminId = userRepository.findByUsername("admin").orElseThrow().getId();
        mvc.perform(put("/api/admin/users/" + adminId + "/active").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isBadRequest());
    }
}
