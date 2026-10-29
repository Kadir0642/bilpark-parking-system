package com.bilpark.backend;

import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.SyncEvent;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.AuditLogRepository;
import com.bilpark.backend.repository.ShiftRepository;
import com.bilpark.backend.repository.SyncEventRepository;
import com.bilpark.backend.repository.UserRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5 / C1-C3: user management, own password, shift list. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAdminApiIntegrationTest {

    private static final String PASS = "Sifre-Test-1234";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired ZoneRepository zoneRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired SyncEventRepository syncEventRepository;
    @Autowired AuditLogRepository auditLogRepository;

    private String tokenOf(ResultActions r) throws Exception {
        JsonNode n = mapper.readTree(r.andReturn().getResponse().getContentAsString());
        return "Bearer " + n.get("token").asString();
    }

    private String login(String user, String pass) throws Exception {
        return tokenOf(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}")).andExpect(status().isOk()));
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass"); }

    private static String uname() { return "t_" + UUID.randomUUID().toString().substring(0, 12); }

    private Zone zone(int index) { return zoneRepository.findAllByOrderByStreetAscZoneNumberAsc().get(index); }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode createOfficer(String admin, String username, Zone z) throws Exception {
        return json(mvc.perform(post("/api/admin/users").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"fullName\":\"Test Personel\",\"password\":\"" + PASS
                                + "\",\"role\":\"OFFICER\",\"zoneId\":" + z.getId() + "}"))
                .andExpect(status().isCreated()));
    }

    // ---- C1 ------------------------------------------------------------------------------------

    @Test
    void createEditZoneAndListFlow() throws Exception {
        String admin = admin();
        String u = uname();
        Zone z1 = zone(0), z2 = zone(1);
        JsonNode created = createOfficer(admin, u, z1);
        long id = created.get("id").asLong();
        assertEquals(u, created.get("username").asString());
        assertEquals("OFFICER", created.get("role").asString());
        assertEquals(z1.getId(), created.get("zoneId").asLong());
        assertEquals(z1.getStreet().name(), created.get("streetCode").asString());
        assertNotNull(created.get("createdAt"));
        assertFalse(created.toString().contains(PASS));

        // the new officer can log in
        login(u, PASS);

        // partial edit: name only keeps role and zone
        mvc.perform(put("/api/admin/users/" + id).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Yeni Ad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Yeni Ad"))
                .andExpect(jsonPath("$.zoneId").value(z1.getId()));

        // zone assignment emits USER_UPDATED
        long before = syncEventRepository.latestVersion();
        mvc.perform(put("/api/admin/users/" + id + "/zone").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"zoneId\":" + z2.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zoneId").value(z2.getId()));
        List<SyncEvent> events = syncEventRepository.findAfter(before, org.springframework.data.domain.PageRequest.of(0, 50));
        assertTrue(events.stream().anyMatch(e -> e.getType() == SyncEventType.USER_UPDATED
                && Long.valueOf(id).equals(e.getTargetUserId())));

        // listed with the new keys
        String list = mvc.perform(get("/api/admin/users").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode mine = null;
        for (JsonNode n : mapper.readTree(list)) if (n.get("id").asLong() == id) mine = n;
        assertNotNull(mine);
        assertEquals("Yeni Ad", mine.get("fullName").asString());
        assertTrue(mine.has("streetCode") && mine.has("createdAt") && mine.has("lastLoginTime") && mine.has("active"));

        // audit rows exist and hold no password
        assertTrue(auditLogRepository.findAll().stream().anyMatch(a ->
                "USER_CREATED".equals(a.getAction().name()) && String.valueOf(id).equals(a.getEntityId())));
        assertTrue(auditLogRepository.findAll().stream().noneMatch(a ->
                (a.getAfterData() != null && a.getAfterData().contains(PASS))));
    }

    @Test
    void createValidation() throws Exception {
        String admin = admin();
        Zone z = zone(0);
        String u = uname();
        createOfficer(admin, u, z);

        // duplicate (case-insensitive)
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + u.toUpperCase() + "\",\"password\":\"" + PASS
                                + "\",\"role\":\"OFFICER\",\"zoneId\":" + z.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        // bad username
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a b\",\"password\":\"" + PASS + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        // developer role refused
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uname() + "\",\"password\":\"" + PASS + "\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isBadRequest());
        // officer needs a zone
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uname() + "\",\"password\":\"" + PASS + "\",\"role\":\"OFFICER\"}"))
                .andExpect(status().isBadRequest());
        // unknown zone
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uname() + "\",\"password\":\"" + PASS
                                + "\",\"role\":\"OFFICER\",\"zoneId\":999999}"))
                .andExpect(status().isNotFound());
        // weak password
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uname() + "\",\"password\":\"abc\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        // admin without a zone is fine
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uname() + "\",\"password\":\"" + PASS + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.zoneId").doesNotExist());
    }

    @Test
    void roleChangeRules() throws Exception {
        String admin = admin();
        long adminId = userRepository.findByUsername("admin").orElseThrow().getId();
        // cannot change own role
        mvc.perform(put("/api/admin/users/" + adminId).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OFFICER\",\"zoneId\":" + zone(0).getId() + "}"))
                .andExpect(status().isBadRequest());

        String u = uname();
        long id = createOfficer(admin, u, zone(0)).get("id").asLong();
        // officer -> admin clears the zone
        mvc.perform(put("/api/admin/users/" + id).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.zoneId").doesNotExist());
        // admin -> officer needs a zone
        mvc.perform(put("/api/admin/users/" + id).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OFFICER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/users/" + id).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OFFICER\",\"zoneId\":" + zone(1).getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.zoneId").value(zone(1).getId()));
    }

    @Test
    void zoneAssignmentOnlyForOfficers() throws Exception {
        String admin = admin();
        long adminId = userRepository.findByUsername("admin").orElseThrow().getId();
        mvc.perform(put("/api/admin/users/" + adminId + "/zone").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"zoneId\":" + zone(0).getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPasswordInvalidatesOldTokenAndNewPasswordWorks() throws Exception {
        String admin = admin();
        String u = uname();
        long id = createOfficer(admin, u, zone(0)).get("id").asLong();
        String oldToken = login(u, PASS);
        mvc.perform(get("/api/me").header("Authorization", oldToken)).andExpect(status().isOk());

        Thread.sleep(1100); // JWT iat has second precision
        mvc.perform(post("/api/admin/users/" + id + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Yeni-Sifre-5678\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + u + "\",\"password\":\"" + PASS + "\"}"))
                .andExpect(status().isUnauthorized());
        String fresh = login(u, "Yeni-Sifre-5678");
        mvc.perform(get("/api/me").header("Authorization", fresh)).andExpect(status().isOk());

        // weak new password is refused
        mvc.perform(post("/api/admin/users/" + id + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"12345678\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
    }

    @Test
    void resetPasswordRefusesAdminsAndDeveloper() throws Exception {
        String admin = admin();
        long adminId = userRepository.findByUsername("admin").orElseThrow().getId();
        mvc.perform(post("/api/admin/users/" + adminId + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Yeni-Sifre-5678\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROTECTED_ACCOUNT"));

        long devId = userRepository.findByUsername("gelistirici").orElseThrow().getId();
        mvc.perform(post("/api/admin/users/" + devId + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Yeni-Sifre-5678\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PROTECTED_ACCOUNT"));
        mvc.perform(put("/api/admin/users/" + devId + "/active").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/users/" + devId).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"x\"}"))
                .andExpect(status().isForbidden());
        // the developer stays listed
        assertTrue(mvc.perform(get("/api/admin/users").header("Authorization", admin))
                .andReturn().getResponse().getContentAsString().contains("gelistirici"));
    }

    @Test
    void deactivationEmitsSyncEventClosesShiftAndAudits() throws Exception {
        String admin = admin();
        String u = uname();
        long id = createOfficer(admin, u, zone(0)).get("id").asLong();
        String token = login(u, PASS);
        User user = userRepository.findById(id).orElseThrow();
        assertEquals(1, shiftRepository.findByUserAndEndTimeIsNull(user).size());

        long before = syncEventRepository.latestVersion();
        mvc.perform(put("/api/admin/users/" + id + "/active").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        List<SyncEvent> events = syncEventRepository.findAfter(before, org.springframework.data.domain.PageRequest.of(0, 50));
        assertTrue(events.stream().anyMatch(e -> e.getType() == SyncEventType.USER_DEACTIVATED
                && Long.valueOf(id).equals(e.getTargetUserId())));
        assertEquals(0, shiftRepository.findByUserAndEndTimeIsNull(user).size());
        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isUnauthorized());

        mvc.perform(put("/api/admin/users/" + id + "/active").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        var actions = auditLogRepository.findAll().stream()
                .filter(a -> String.valueOf(id).equals(a.getEntityId()) && "User".equals(a.getEntityType()))
                .map(a -> a.getAction().name()).toList();
        assertTrue(actions.contains("USER_DEACTIVATED"));
        assertTrue(actions.contains("USER_ACTIVATED"));
    }

    @Test
    void officerAndAnonymousCannotManageUsers() throws Exception {
        String officer = login("tevfik", "test-officer-pass");
        mvc.perform(post("/api/admin/users").header("Authorization", officer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\",\"password\":\"" + PASS + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/users/1").header("Authorization", officer).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/1/reset-password").header("Authorization", officer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/shifts").header("Authorization", officer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    // ---- C2 ------------------------------------------------------------------------------------

    @Test
    void meReturnsOwnProfile() throws Exception {
        String officer = login("tevfik", "test-officer-pass");
        mvc.perform(get("/api/me").header("Authorization", officer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("tevfik"))
                .andExpect(jsonPath("$.role").value("OFFICER"))
                .andExpect(jsonPath("$.zoneName").exists());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void changeOwnPasswordIssuesNewTokenAndKillsTheOldOne() throws Exception {
        String admin = admin();
        String u = uname();
        createOfficer(admin, u, zone(0));
        String oldToken = login(u, PASS);

        // wrong current password
        mvc.perform(post("/api/me/password").header("Authorization", oldToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"yanlis-Sifre1\",\"newPassword\":\"Baska-Sifre-4321\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        // weak new password
        mvc.perform(post("/api/me/password").header("Authorization", oldToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASS + "\",\"newPassword\":\"kisa\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));

        Thread.sleep(1100);
        String body = mvc.perform(post("/api/me/password").header("Authorization", oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASS + "\",\"newPassword\":\"Baska-Sifre-4321\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Şifre değiştirildi."))
                .andReturn().getResponse().getContentAsString();
        String newToken = "Bearer " + mapper.readTree(body).get("token").asString();

        mvc.perform(get("/api/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", newToken)).andExpect(status().isOk());
        login(u, "Baska-Sifre-4321");
        assertTrue(auditLogRepository.findAll().stream().anyMatch(a ->
                a.getAction().name().equals("PASSWORD_CHANGED") && u.equals(a.getActorUsername())));
    }

    // ---- C3 ------------------------------------------------------------------------------------

    @Test
    void shiftListFiltersAndPages() throws Exception {
        String admin = admin();
        String u = uname();
        JsonNode created = createOfficer(admin, u, zone(0));
        long id = created.get("id").asLong();
        User user = userRepository.findById(id).orElseThrow();

        LocalDateTime base = LocalDateTime.now().minusDays(40).withNano(0);
        for (int i = 0; i < 3; i++) {
            Shift s = new Shift(user, zone(0), "dev-" + i, base.plusDays(i));
            s.setEndTime(base.plusDays(i).plusHours(2));
            shiftRepository.save(s);
        }
        Shift other = new Shift(user, zone(1), "dev-z", base.plusDays(10));
        shiftRepository.save(other); // open
        String from = base.toLocalDate().toString();
        String to = base.plusDays(10).toLocalDate().toString();

        mvc.perform(get("/api/admin/shifts").header("Authorization", admin)
                        .param("userId", String.valueOf(id)).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].open").value(true))
                .andExpect(jsonPath("$.content[0].deviceId").value("dev-z"))
                .andExpect(jsonPath("$.content[1].durationMinutes").value(120))
                .andExpect(jsonPath("$.content[1].username").value(u))
                .andExpect(jsonPath("$.content[1].fullName").value("Test Personel"));

        // zone filter
        mvc.perform(get("/api/admin/shifts").header("Authorization", admin).param("userId", String.valueOf(id))
                        .param("zoneId", String.valueOf(zone(1).getId())).param("from", from).param("to", to))
                .andExpect(jsonPath("$.totalElements").value(1));
        // date range excludes the later shifts (to is inclusive)
        mvc.perform(get("/api/admin/shifts").header("Authorization", admin).param("userId", String.valueOf(id))
                        .param("from", from).param("to", from))
                .andExpect(jsonPath("$.totalElements").value(1));
        // paging
        mvc.perform(get("/api/admin/shifts").header("Authorization", admin).param("userId", String.valueOf(id))
                        .param("from", from).param("to", to).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(1));
        // default range (last 7 days) does not include 40-day-old shifts
        mvc.perform(get("/api/admin/shifts").header("Authorization", admin).param("userId", String.valueOf(id)))
                .andExpect(jsonPath("$.totalElements").value(0));
        // bad date
        mvc.perform(get("/api/admin/shifts").header("Authorization", admin).param("from", "31.12.2026"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
