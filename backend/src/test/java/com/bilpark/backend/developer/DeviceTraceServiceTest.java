package com.bilpark.backend.developer;

import com.bilpark.backend.auth.DeviceTraceService;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Device trace: last_seen_at / app version / device, written at most once per 2 minutes per user. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceTraceServiceTest {

    /** Clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T07:00:00Z");
        private final ZoneId zone = ZoneId.of("Europe/Istanbul");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;
    @Autowired com.bilpark.backend.auth.JwtUtil jwtUtil;
    @Autowired com.bilpark.backend.auth.CustomUserDetailsService userDetailsService;

    private final MutableClock clock = new MutableClock();
    private DeviceTraceService service;

    @BeforeEach
    void setUp() {
        service = new DeviceTraceService(jdbc, clock, Runnable::run); // direct executor: the write is visible at once
        reset();
    }

    @AfterEach
    void reset() {
        jdbc.update("UPDATE users SET last_seen_at = NULL, last_app_version = NULL, last_device = NULL WHERE username = 'tevfik'");
    }

    private User tevfik() {
        return users.findByUsername("tevfik").orElseThrow();
    }

    @Test
    void firstTouchWritesEverything() {
        service.touch("tevfik", "1.0.0+1", "SM-M127F / Android 13");
        User u = tevfik();
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 0), u.getLastSeenAt()); // Istanbul = UTC+3
        assertEquals("1.0.0+1", u.getLastAppVersion());
        assertEquals("SM-M127F / Android 13", u.getLastDevice());
    }

    @Test
    void secondWriteWithinTwoMinutesIsSkipped_thenAllowed() {
        service.touch("tevfik", "1.0.0+1", "Pixel");
        LocalDateTime first = tevfik().getLastSeenAt();

        clock.advance(Duration.ofSeconds(119));
        service.touch("tevfik", "1.0.1+2", "Pixel 8");
        assertEquals(first, tevfik().getLastSeenAt(), "119 s later: no write");
        assertEquals("1.0.0+1", tevfik().getLastAppVersion());

        clock.advance(Duration.ofSeconds(1));
        service.touch("tevfik", "1.0.1+2", "Pixel 8");
        assertEquals(first.plusMinutes(2), tevfik().getLastSeenAt(), "120 s later: written");
        assertEquals("1.0.1+2", tevfik().getLastAppVersion());
        assertEquals("Pixel 8", tevfik().getLastDevice());
    }

    @Test
    void requestWithoutHeadersKeepsKnownVersionAndDevice() {
        service.touch("tevfik", "1.0.0+1", "Pixel");
        clock.advance(Duration.ofMinutes(3));
        service.touch("tevfik", null, null); // web panel
        User u = tevfik();
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 3), u.getLastSeenAt());
        assertEquals("1.0.0+1", u.getLastAppVersion());
        assertEquals("Pixel", u.getLastDevice());
    }

    @Test
    void headerValuesAreCutAndCleaned() {
        service.touch("tevfik", "1.0.0+1\r\n" + "x".repeat(100), "D".repeat(200));
        User u = tevfik();
        assertEquals(40, u.getLastAppVersion().length());
        assertFalse(u.getLastAppVersion().contains("\n"));
        assertEquals(80, u.getLastDevice().length());
    }

    @Test
    void unknownUserOrDatabaseErrorNeverThrows() {
        assertDoesNotThrow(() -> service.touch("nobody-here", "1.0.0", "x"));
        assertDoesNotThrow(() -> service.touch(null, "1.0.0", "x"));
        DeviceTraceService broken = new DeviceTraceService(new JdbcTemplate() {
            @Override public int update(String sql, Object... args) { throw new IllegalStateException("db down"); }
        }, clock, Runnable::run);
        assertDoesNotThrow(() -> broken.touch("tevfik", "1.0.0", "x"));
    }

    @Test
    void authenticatedRequestRecordsHeaders_anonymousDoesNot() throws Exception {
        // A fresh user (the real service is shared by all tests; its 2-minute throttle must not be used up yet)
        String name = "iz" + java.util.UUID.randomUUID().toString().substring(0, 8);
        User u0 = new User(name, "x", Role.OFFICER, users.findByUsername("tevfik").orElseThrow().getAssignedZone());
        users.save(u0);
        try {
            String token = jwtUtil.generateToken(userDetailsService.loadUserByUsername(name));

            mvc.perform(get("/api/health").header("X-App-Version", "7.7.7+7")).andExpect(status().isOk());
            assertNull(users.findByUsername(name).orElseThrow().getLastAppVersion(), "anonymous request leaves no trace");

            mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)
                            .header("X-App-Version", "7.7.7+7").header("X-Device", "TestPhone / Android 14"))
                    .andExpect(status().isOk());
            // the write happens on the background thread: wait for it
            long deadline = System.currentTimeMillis() + 5000;
            while (users.findByUsername(name).orElseThrow().getLastAppVersion() == null
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            User u = users.findByUsername(name).orElseThrow();
            assertEquals("7.7.7+7", u.getLastAppVersion());
            assertEquals("TestPhone / Android 14", u.getLastDevice());
            assertNotNull(u.getLastSeenAt());
        } finally {
            users.delete(users.findByUsername(name).orElseThrow());
        }
    }
}
