package com.bilpark.backend;

import com.bilpark.backend.license.LicenseService;
import com.bilpark.backend.model.LicenseInfo;
import com.bilpark.backend.repository.LicenseInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** License read-only mode: writes are 423 READ_ONLY, reads / login / health stay open, GRACE still writes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadOnlyModeIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private LicenseInfoRepository licenseRepository;
    @Autowired private LicenseService licenseService;

    private LocalDate savedExpiresOn;
    private Integer savedGrace;

    @BeforeEach
    void save() {
        LicenseInfo l = licenseService.current();
        savedExpiresOn = l.getExpiresOn();
        savedGrace = l.getGraceDays();
    }

    @AfterEach
    void restore() {
        LicenseInfo l = licenseService.current();
        l.setExpiresOn(savedExpiresOn);
        l.setGraceDays(savedGrace);
        licenseRepository.save(l);
        licenseService.evictCache();
    }

    private void setLicense(LocalDate expiresOn, int grace) {
        LicenseInfo l = licenseService.current();
        l.setExpiresOn(expiresOn);
        l.setGraceDays(grace);
        licenseRepository.save(l);
        licenseService.evictCache();
    }

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    private static final String DRAFT = "{\"smallFirstHour\":30,\"smallExtraHour\":20,\"largeFirstHour\":60,"
            + "\"largeExtraHour\":40,\"freeMinutes\":10,\"paidStart\":\"09:00\",\"paidEnd\":\"20:00\","
            + "\"subscriptionSmallMonthly\":2800,\"subscriptionLargeMonthly\":3300}";

    @Test
    void readOnly_blocksWrites_allowsReadsLoginHealth() throws Exception {
        String adm = login("admin", "test-admin-pass");
        String off = login("tevfik", "test-officer-pass");
        String plate = RecordCancellationIntegrationTest.plate();
        // an active vehicle (created while writable) for the check-out / runaway / cancel attempts
        mvc.perform(post("/api/parking/check-in").header("Authorization", off)
                .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1")).andExpect(status().isOk());

        setLicense(LocalDate.now().minusDays(30), 5);

        String msg = LicenseService.READ_ONLY_MESSAGE;
        mvc.perform(post("/api/parking/check-in").header("Authorization", off)
                        .param("plate", RecordCancellationIntegrationTest.plate()).param("street", "TEVFIK_BEY")
                        .param("zoneId", "1"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("READ_ONLY"))
                .andExpect(jsonPath("$.error").value(msg));
        mvc.perform(post("/api/parking/check-out").header("Authorization", off).param("plate", plate))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("READ_ONLY"));
        mvc.perform(post("/api/parking/runaway").header("Authorization", off).param("plate", plate))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("READ_ONLY"));
        mvc.perform(post("/api/parking/spots/cancel").header("Authorization", off).param("plate", plate)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Yanlış giriş\"}"))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("READ_ONLY"));
        mvc.perform(post("/api/admin/tariffs").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("READ_ONLY"));
        mvc.perform(post("/api/admin/records/1/cancel").header("Authorization", adm)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Yanlış kayıt\"}"))
                .andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("READ_ONLY"));

        // reads, login, health stay open
        mvc.perform(get("/api/parking/spots").header("Authorization", off)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/records/cancelled").header("Authorization", adm)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").header("Authorization", adm)).andExpect(status().isOk());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        login("admin", "test-admin-pass");
    }

    @Test
    void grace_stillAllowsWrites() throws Exception {
        String off = login("tevfik", "test-officer-pass");
        setLicense(LocalDate.now().minusDays(2), 5);
        String plate = RecordCancellationIntegrationTest.plate();
        mvc.perform(post("/api/parking/check-in").header("Authorization", off)
                .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1")).andExpect(status().isOk());
        mvc.perform(post("/api/parking/check-out").header("Authorization", off).param("plate", plate))
                .andExpect(status().isOk());
    }
}
