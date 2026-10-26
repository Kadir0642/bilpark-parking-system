package com.bilpark.backend;

import com.bilpark.backend.license.LicenseService;
import com.bilpark.backend.model.AuditLog;
import com.bilpark.backend.model.LicenseInfo;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.AuditLogRepository;
import com.bilpark.backend.repository.LicenseInfoRepository;
import com.bilpark.backend.repository.ZoneRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stage 5 / C4-C5: license status, developer endpoints and their access rules. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeveloperApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired LicenseService licenseService;
    @Autowired LicenseInfoRepository licenseRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired ZoneRepository zoneRepository;

    private LocalDate savedExpiry;
    private Integer savedGrace;
    private String savedLicensee;
    private String savedNote;

    @BeforeEach
    void saveLicense() {
        LicenseInfo l = licenseService.current();
        savedExpiry = l.getExpiresOn();
        savedGrace = l.getGraceDays();
        savedLicensee = l.getLicensee();
        savedNote = l.getNote();
    }

    @AfterEach
    void restoreLicense() {
        LicenseInfo l = licenseService.current();
        l.setExpiresOn(savedExpiry);
        l.setGraceDays(savedGrace);
        l.setLicensee(savedLicensee);
        l.setNote(savedNote);
        licenseRepository.save(l);
        licenseService.evictCache();
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

    private long auditCount(String action, String actor) {
        return auditLogRepository.findAll().stream()
                .filter(a -> a.getAction().name().equals(action) && actor.equals(a.getActorUsername())).count();
    }

    // ---- license --------------------------------------------------------------------------------

    @Test
    void licenseStatusVisibleToEveryRole() throws Exception {
        for (String token : List.of(admin(), officer(), dev())) {
            mvc.perform(get("/api/license/status").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").exists())
                    .andExpect(jsonPath("$.expiresOn").exists())
                    .andExpect(jsonPath("$.graceDays").exists())
                    .andExpect(jsonPath("$.readOnly").exists());
        }
        mvc.perform(get("/api/license/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void developerUpdatesLicenseAndItIsAudited() throws Exception {
        long before = auditCount("LICENSE_UPDATED", "gelistirici");
        LocalDate newExpiry = LocalDate.now().plusDays(400);
        mvc.perform(put("/api/developer/license").header("Authorization", dev())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresOn\":\"" + newExpiry + "\",\"graceDays\":20,\"licensee\":\"Test Belediyesi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresOn").value(newExpiry.toString()))
                .andExpect(jsonPath("$.graceDays").value(20))
                .andExpect(jsonPath("$.licensee").value("Test Belediyesi"))
                .andExpect(jsonPath("$.state").value("ACTIVE"));
        assertEquals(before + 1, auditCount("LICENSE_UPDATED", "gelistirici"));
        mvc.perform(get("/api/license/status").header("Authorization", officer()))
                .andExpect(jsonPath("$.licensee").value("Test Belediyesi"));
    }

    @Test
    void licenseUpdateValidation() throws Exception {
        String dev = dev();
        mvc.perform(put("/api/developer/license").header("Authorization", dev).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresOn\":\"01.11.2027\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(put("/api/developer/license").header("Authorization", dev).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graceDays\":5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/developer/license").header("Authorization", dev).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresOn\":\"2027-11-01\",\"graceDays\":-1}"))
                .andExpect(status().isBadRequest());
    }

    // ---- health / errors ------------------------------------------------------------------------

    @Test
    void healthReportsSystemStateAndIsAudited() throws Exception {
        long before = auditCount("DEV_HEALTH_VIEWED", "gelistirici");
        mvc.perform(get("/api/developer/health").header("Authorization", dev()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.ok").value(true))
                .andExpect(jsonPath("$.database.latencyMs").isNumber())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber())
                .andExpect(jsonPath("$.javaVersion").isString())
                .andExpect(jsonPath("$.memory.usedMb").isNumber())
                .andExpect(jsonPath("$.memory.maxMb").isNumber())
                .andExpect(jsonPath("$.counts.users").isNumber())
                .andExpect(jsonPath("$.counts.activeSpots").isNumber())
                .andExpect(jsonPath("$.counts.parkingRecords").isNumber())
                .andExpect(jsonPath("$.counts.openShifts").isNumber())
                .andExpect(jsonPath("$.license.state").exists())
                .andExpect(jsonPath("$.errorsLast24h").isNumber())
                .andExpect(jsonPath("$.time").exists());
        assertEquals(before + 1, auditCount("DEV_HEALTH_VIEWED", "gelistirici"));
    }

    @Test
    void errorsEndpointAuditedAndLimited() throws Exception {
        long before = auditCount("DEV_ERRORS_VIEWED", "gelistirici");
        mvc.perform(get("/api/developer/errors?limit=5").header("Authorization", dev()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        assertEquals(before + 1, auditCount("DEV_ERRORS_VIEWED", "gelistirici"));
    }

    // ---- admin password reset -------------------------------------------------------------------

    @Test
    void developerResetsAnAdminPassword() throws Exception {
        String name = "t_" + UUID.randomUUID().toString().substring(0, 12);
        mvc.perform(post("/api/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"Eski-Sifre-1234\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());
        String oldToken = login(name, "Eski-Sifre-1234");

        long before = auditCount("DEV_ADMIN_PASSWORD_RESET", "gelistirici");
        Thread.sleep(1100);
        mvc.perform(post("/api/developer/admin-password-reset").header("Authorization", dev())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"newPassword\":\"Yeni-Sifre-9876\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
        login(name, "Yeni-Sifre-9876");
        assertEquals(before + 1, auditCount("DEV_ADMIN_PASSWORD_RESET", "gelistirici"));
        AuditLog row = auditLogRepository.findAll().stream()
                .filter(a -> a.getAction().name().equals("DEV_ADMIN_PASSWORD_RESET")).findFirst().orElseThrow();
        assertFalse(String.valueOf(row.getAfterData()).contains("Yeni-Sifre"));
        assertFalse(String.valueOf(row.getSummary()).contains("Yeni-Sifre"));
    }

    @Test
    void adminResetOnlyTargetsAdmins() throws Exception {
        String dev = dev();
        mvc.perform(post("/api/developer/admin-password-reset").header("Authorization", dev)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tevfik\",\"newPassword\":\"Yeni-Sifre-9876\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROTECTED_ACCOUNT"));
        mvc.perform(post("/api/developer/admin-password-reset").header("Authorization", dev)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"yok_boyle_biri\",\"newPassword\":\"Yeni-Sifre-9876\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/developer/admin-password-reset").header("Authorization", dev)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"gelistirici\",\"newPassword\":\"Yeni-Sifre-9876\"}"))
                .andExpect(status().isBadRequest());
        // the seeded accounts still log in with their own passwords
        admin();
        officer();
    }

    // ---- access rules ---------------------------------------------------------------------------

    @Test
    void developerEndpointsAreDeveloperOnly() throws Exception {
        for (String token : List.of(admin(), officer())) {
            mvc.perform(get("/api/developer/health").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get("/api/developer/errors").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(put("/api/developer/license").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"expiresOn\":\"2030-01-01\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/developer/admin-password-reset").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"newPassword\":\"x1y2z3w4\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/developer/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void developerHasNoAccessToOperationalData() throws Exception {
        String dev = dev();
        Zone z = zoneRepository.findAllByOrderByStreetAscZoneNumberAsc().get(0);
        mvc.perform(get("/api/parking/spots").header("Authorization", dev)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization", dev)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit").header("Authorization", dev)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/shifts").header("Authorization", dev)).andExpect(status().isForbidden());
        mvc.perform(get("/api/parking/income").header("Authorization", dev)).andExpect(status().isForbidden());
        mvc.perform(post("/api/parking/checkin").header("Authorization", dev).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plate\":\"34DEV123\",\"zoneId\":" + z.getId() + "}")).andExpect(status().isForbidden());
        // but may see his own account
        mvc.perform(get("/api/me").header("Authorization", dev)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
    }
}
