package com.bilpark.backend.notification;

import com.bilpark.backend.developer.VersionChangeNotifier;
import com.bilpark.backend.license.LicenseService;
import com.bilpark.backend.model.LicenseInfo;
import com.bilpark.backend.repository.LicenseInfoRepository;
import com.bilpark.backend.settings.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/api-bildirimler.md: notification creation, read state, access rules and the audit maintenance filter. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LicenseService licenseService;
    @Autowired LicenseInfoRepository licenseRepository;
    @Autowired VersionChangeNotifier versionNotifier;
    @Autowired SettingsService settings;

    private LocalDate savedExpiry;
    private Integer savedGrace;
    private String savedLicensee;
    private String savedNote;

    @BeforeEach
    void init() {
        LicenseInfo l = licenseService.current();
        savedExpiry = l.getExpiresOn();
        savedGrace = l.getGraceDays();
        savedLicensee = l.getLicensee();
        savedNote = l.getNote();
        clearNotifications();
    }

    @AfterEach
    void cleanUp() {
        LicenseInfo l = licenseService.current();
        l.setExpiresOn(savedExpiry);
        l.setGraceDays(savedGrace);
        l.setLicensee(savedLicensee);
        l.setNote(savedNote);
        licenseRepository.save(l);
        licenseService.evictCache();
        jdbc.update("DELETE FROM app_settings WHERE setting_key = ?", SettingsService.KEY_LAST_APP_VERSION);
        clearNotifications();
    }

    private void clearNotifications() {
        jdbc.update("DELETE FROM admin_notification_reads");
        jdbc.update("DELETE FROM admin_notifications");
    }

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asString();
    }

    private String dev() throws Exception { return login("gelistirici", "test-developer-pass1"); }
    private String admin() throws Exception { return login("admin", "test-admin-pass"); }
    private String officer() throws Exception { return login("tevfik", "test-officer-pass"); }

    private JsonNode inbox(String token) throws Exception {
        String body = mvc.perform(get("/api/admin/notifications").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private JsonNode announce(String json) throws Exception {
        String body = mvc.perform(post("/api/developer/announcements").header("Authorization", dev())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private long count(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_notifications WHERE type = ?", Long.class, type);
    }

    // ---- creation ---------------------------------------------------------------------------------

    @Test
    void licenseChangeCreatesNotification() throws Exception {
        mvc.perform(put("/api/developer/license").header("Authorization", dev())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresOn\":\"2027-11-01\",\"graceDays\":15}"))
                .andExpect(status().isOk());
        JsonNode inbox = inbox(admin());
        assertEquals(1, inbox.get("unread").asInt());
        JsonNode n = inbox.get("items").get(0);
        assertEquals("LICENSE_UPDATED", n.get("type").asString());
        assertEquals("Lisans güncellendi: bitiş 1 Kasım 2027", n.get("title").asString());
        assertFalse(n.get("read").asBoolean());
    }

    @Test
    void adminPasswordResetCreatesNotification() throws Exception {
        String name = "n_" + UUID.randomUUID().toString().substring(0, 12);
        mvc.perform(post("/api/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"Eski-Sifre-1234\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/developer/admin-password-reset").header("Authorization", dev())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"newPassword\":\"Yeni-Sifre-9876\"}"))
                .andExpect(status().isOk());
        JsonNode items = inbox(admin()).get("items");
        assertEquals(1, items.size());
        assertEquals("ADMIN_PASSWORD_RESET", items.get(0).get("type").asString());
        assertEquals(name + " hesabının şifresi sıfırlandı", items.get(0).get("title").asString());
        assertFalse(items.toString().contains("Yeni-Sifre"));
    }

    @Test
    void announcementAndMaintenanceAreCreatedAndValidated() throws Exception {
        announce("{\"type\":\"ANNOUNCEMENT\",\"title\":\"Yeni özellik\",\"body\":\"Raporlar hızlandı.\"}");
        String until = LocalDateTime.now().plusDays(3).withNano(0).toString();
        JsonNode m = announce("{\"type\":\"MAINTENANCE\",\"title\":\"Planlı bakım: gece\",\"validUntil\":\"" + until + "\"}");
        assertEquals("MAINTENANCE", m.get("type").asString());
        assertFalse(m.get("validUntil").isNull());
        assertEquals(1, count("ANNOUNCEMENT"));
        assertEquals(1, count("MAINTENANCE"));

        String dev = dev();
        // maintenance without validUntil, in the past, or malformed
        for (String bad : new String[]{
                "{\"type\":\"MAINTENANCE\",\"title\":\"Planlı bakım\"}",
                "{\"type\":\"MAINTENANCE\",\"title\":\"Planlı bakım\",\"validUntil\":\"2020-01-01T10:00:00\"}",
                "{\"type\":\"MAINTENANCE\",\"title\":\"Planlı bakım\",\"validUntil\":\"yarin\"}",
                "{\"type\":\"ANNOUNCEMENT\",\"title\":\"ab\"}",
                "{\"type\":\"ANNOUNCEMENT\",\"title\":\"" + "x".repeat(121) + "\"}",
                "{\"type\":\"ANNOUNCEMENT\",\"title\":\"Geçerli başlık\",\"body\":\"" + "y".repeat(2001) + "\"}",
                "{\"type\":\"OTHER\",\"title\":\"Geçerli başlık\"}"}) {
            mvc.perform(post("/api/developer/announcements").header("Authorization", dev)
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM admin_notifications", Long.class));

        // an offset is accepted and converted to Istanbul time
        String utc = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(2).withNano(0).toString();
        announce("{\"type\":\"MAINTENANCE\",\"title\":\"Planlı bakım UTC\",\"validUntil\":\"" + utc + "\"}");

        // the developer sees what was sent, with the read count
        String list = mvc.perform(get("/api/developer/announcements").header("Authorization", dev))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].readCount").value(0))
                .andReturn().getResponse().getContentAsString();
        assertEquals(3, mapper.readTree(list).size());

        // audited as DEV_ANNOUNCEMENT_CREATED
        assertTrue(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'DEV_ANNOUNCEMENT_CREATED' AND actor_role = 'DEVELOPER'",
                Long.class) >= 3);
    }

    @Test
    void versionChangeCreatesNotificationButFirstInstallDoesNot() {
        assertFalse(versionNotifier.check(null), "unknown version: nothing happens");
        assertFalse(versionNotifier.check("abc111"), "first installation only stores the version");
        assertEquals(0, count("SYSTEM_UPDATED"));
        assertEquals("abc111", settings.get(SettingsService.KEY_LAST_APP_VERSION).orElseThrow());

        assertFalse(versionNotifier.check("abc111"), "same version: nothing");
        assertEquals(0, count("SYSTEM_UPDATED"));

        assertTrue(versionNotifier.check("abc222"));
        assertEquals(1, count("SYSTEM_UPDATED"));
        assertEquals("abc222", settings.get(SettingsService.KEY_LAST_APP_VERSION).orElseThrow());
        assertFalse(versionNotifier.check("abc222"));
        assertEquals(1, count("SYSTEM_UPDATED"));
    }

    // ---- read state ---------------------------------------------------------------------------------

    @Test
    void readMarksAreKeptPerUser() throws Exception {
        String second = "n2_" + UUID.randomUUID().toString().substring(0, 12);
        mvc.perform(post("/api/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + second + "\",\"password\":\"Ikinci-Sifre-1234\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());
        String a1 = admin();
        String a2 = login(second, "Ikinci-Sifre-1234");

        long id1 = announce("{\"type\":\"ANNOUNCEMENT\",\"title\":\"Birinci duyuru\"}").get("id").asLong();
        announce("{\"type\":\"ANNOUNCEMENT\",\"title\":\"İkinci duyuru\"}");
        announce("{\"type\":\"ANNOUNCEMENT\",\"title\":\"Üçüncü duyuru\"}");
        assertEquals(3, inbox(a1).get("unread").asInt());
        assertEquals(3, inbox(a2).get("unread").asInt());

        mvc.perform(post("/api/admin/notifications/" + id1 + "/read").header("Authorization", a1))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/notifications/" + id1 + "/read").header("Authorization", a1))
                .andExpect(status().isNoContent()); // again: harmless
        assertEquals(2, inbox(a1).get("unread").asInt());
        assertEquals(3, inbox(a2).get("unread").asInt(), "another admin is unaffected");
        JsonNode items = inbox(a1).get("items");
        assertEquals("Üçüncü duyuru", items.get(0).get("title").asString(), "newest first");
        assertTrue(items.get(2).get("read").asBoolean());
        assertFalse(items.get(0).get("read").asBoolean());

        mvc.perform(post("/api/admin/notifications/999999/read").header("Authorization", a1))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/admin/notifications/read-all").header("Authorization", a1)).andExpect(status().isNoContent());
        assertEquals(0, inbox(a1).get("unread").asInt());
        assertEquals(3, inbox(a2).get("unread").asInt());

        announce("{\"type\":\"ANNOUNCEMENT\",\"title\":\"Dördüncü duyuru\"}");
        assertEquals(1, inbox(a1).get("unread").asInt());
        assertEquals(4, inbox(a2).get("unread").asInt());

        // the developer's list shows how many read each announcement
        mvc.perform(get("/api/developer/announcements").header("Authorization", dev()))
                .andExpect(jsonPath("$[0].readCount").value(0))
                .andExpect(jsonPath("$[3].readCount").value(1));
        // reading is not audited
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE entity_type = 'Notification' AND action <> 'DEV_ANNOUNCEMENT_CREATED'",
                Long.class));
    }

    // ---- access rules ---------------------------------------------------------------------------------

    @Test
    void accessRules() throws Exception {
        String officer = officer();
        String dev = dev();
        String admin = admin();
        for (String token : new String[]{officer, dev}) {
            mvc.perform(get("/api/admin/notifications").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/notifications/1/read").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/notifications/read-all").header("Authorization", token)).andExpect(status().isForbidden());
        }
        for (String token : new String[]{admin, officer}) {
            mvc.perform(post("/api/developer/announcements").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"ANNOUNCEMENT\",\"title\":\"Deneme\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/developer/announcements").header("Authorization", token)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/notifications")).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_notifications", Long.class));
    }

    // ---- audit maintenance filter -----------------------------------------------------------------------

    @Test
    void auditHidesMaintenanceByDefault() throws Exception {
        String dev = dev();
        mvc.perform(get("/api/developer/health").header("Authorization", dev)).andExpect(status().isOk());
        announce("{\"type\":\"ANNOUNCEMENT\",\"title\":\"Denetim deneme\"}");
        String admin = admin();

        String def = mvc.perform(get("/api/admin/audit").header("Authorization", admin).param("size", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode d = mapper.readTree(def);
        long hidden = d.get("maintenanceHidden").asLong();
        assertTrue(hidden >= 2, "health view and announcement are hidden: " + hidden);
        for (JsonNode row : d.get("content")) {
            assertNotEquals("DEVELOPER", row.get("actorRole").asString(""));
            assertFalse(row.get("action").asString().startsWith("DEV_"));
            assertNotEquals("DEVELOPER_ACCOUNT_CREATED", row.get("action").asString());
        }

        String all = mvc.perform(get("/api/admin/audit").header("Authorization", admin)
                        .param("size", "200").param("includeMaintenance", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maintenanceHidden").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode a = mapper.readTree(all);
        assertEquals(d.get("totalElements").asLong() + hidden, a.get("totalElements").asLong());
        boolean sawMaintenance = false;
        for (JsonNode row : a.get("content")) {
            if (row.get("action").asString().startsWith("DEV_")) sawMaintenance = true;
        }
        assertTrue(sawMaintenance);

        // asking for actorRole=DEVELOPER implies includeMaintenance
        mvc.perform(get("/api/admin/audit").header("Authorization", admin).param("actorRole", "DEVELOPER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maintenanceHidden").value(0))
                .andExpect(jsonPath("$.content[0].actorRole").value("DEVELOPER"));

        // the download follows the same switch
        String csvDefault = mvc.perform(get("/api/admin/audit/export").header("Authorization", admin).param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(csvDefault.contains("Geliştirici: duyuru gönderildi"));
        String csvAll = mvc.perform(get("/api/admin/audit/export").header("Authorization", admin)
                        .param("format", "csv").param("includeMaintenance", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(csvAll.contains("Geliştirici: duyuru gönderildi"));

        // the developer's own audit view is not filtered
        mvc.perform(get("/api/developer/audit").header("Authorization", dev).param("size", "5"))
                .andExpect(status().isOk());
    }
}
