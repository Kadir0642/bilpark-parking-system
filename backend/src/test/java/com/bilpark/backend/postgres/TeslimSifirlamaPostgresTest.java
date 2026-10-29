package com.bilpark.backend.postgres;

import com.bilpark.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * scripts/teslim-sifirlama.sql, run with psql exactly as the operator does: without the confirmation line it
 * stops and deletes nothing; with it the operational tables are empty, the kept tables are untouched,
 * one audit row is written, and the application starts again on the reset database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TeslimSifirlamaPostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired JdbcTemplate jdbc;

    private static final List<String> OPERATIONAL = List.of("parking_records", "active_park_spots",
            "check_in_requests", "shifts", "subscriptions", "sync_events", "error_logs", "audit_logs");
    private static final List<String> KEPT = List.of("zones", "streets", "tariff_vehicle_prices", "vehicle_types",
            "app_settings", "license_info", "fiscal_years", "flyway_schema_history");

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    /** Content fingerprint of a table (or of a filtered part of it). */
    private String fingerprint(String from) {
        return jdbc.queryForObject(
                "SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), 'empty') FROM " + from + " t", String.class);
    }

    private void seedDemoData() {
        Long zone = jdbc.queryForObject("SELECT min(id) FROM zones", Long.class);
        Long officer = jdbc.queryForObject("SELECT id FROM users WHERE username = 'tevfik'", Long.class);
        jdbc.update("INSERT INTO users (username, password, role, zone_id, active) VALUES ('demo2', 'x', 'ADMIN', NULL, true)");
        jdbc.update("INSERT INTO users (username, password, role, zone_id, active) VALUES ('ayse', 'x', 'OFFICER', ?, true)", zone);
        Long demoAdmin = jdbc.queryForObject("SELECT id FROM users WHERE username = 'demo2'", Long.class);
        Long tariffId = jdbc.queryForObject("SELECT id FROM tariffs WHERE status = 'ACTIVE'", Long.class);

        // a draft tariff created by the extra admin: kept, but its created_by_id must be cleared
        jdbc.update("""
                INSERT INTO tariffs (status, free_minutes, paid_start, paid_end, note, created_by_id, created_by_username, created_at)
                VALUES ('DRAFT', 5, '08:00', '22:00', 'demo taslak', ?, 'demo2', now())""", demoAdmin);
        jdbc.update("INSERT INTO fiscal_years (start_date, end_date, label, is_active, archived) VALUES ('2026-01-01', '2026-12-31', '2026', true, false)");
        jdbc.update("INSERT INTO app_settings (setting_key, setting_value, updated_at, updated_by) VALUES ('rent.2026', '1200000', now(), 'admin')");

        jdbc.update("""
                INSERT INTO active_park_spots (version, current_plate, street, zone_id, entry_time, current_type, status)
                VALUES (0, '34DEMO1', 'TEVFIK_BEY', ?, now(), 'SMALL', 'ACTIVE')""", zone);
        jdbc.update("""
                INSERT INTO parking_records (license_plate, street, zone_id, entry_time, exit_time, vehicle_type, fee, status,
                                             debt_paid, collected_by_id, collected_by_username, tariff_id, payment_method)
                VALUES ('34DEMO2', 'TEVFIK_BEY', ?, now(), now(), 'SMALL', 25, 'PAID', true, ?, 'tevfik', ?, 'CASH')""",
                zone, officer, tariffId);
        jdbc.update("""
                INSERT INTO subscriptions (license_plate, vehicle_type, start_date, end_date, monthly_fee, status)
                VALUES ('34DEMO3', 'SMALL', '2026-10-01', '2026-10-31', 2500, 'ACTIVE')""");
        jdbc.update("""
                INSERT INTO shifts (user_id, username, zone_id, device_id, start_time, auto_closed)
                VALUES (?, 'tevfik', ?, 'dev-1', now(), false)""", officer, zone);
        jdbc.update("INSERT INTO sync_events (type, scope, payload, created_at) VALUES ('SPOT_ADDED', 'GLOBAL', '{}', now())");
        jdbc.update("INSERT INTO error_logs (created_at, message) VALUES (now(), 'demo hata')");
        jdbc.update("INSERT INTO check_in_requests (request_id, plate, created_at) VALUES ('req-1', '34DEMO1', now())");
        jdbc.update("""
                INSERT INTO audit_logs (created_at, actor_username, action, summary)
                VALUES (now(), 'tevfik', 'VEHICLE_CHECK_IN', 'demo giris')""");
    }

    private ExecResult runScript(boolean approved, String newAdminHash) throws Exception {
        postgres.copyFileToContainer(MountableFile.forHostPath(Path.of("..", "scripts", "teslim-sifirlama.sql")),
                "/tmp/teslim-sifirlama.sql");
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>(List.of("psql", "-U", postgres.getUsername(),
                "-d", postgres.getDatabaseName(), "-v", "ON_ERROR_STOP=1"));
        if (approved) {
            cmd.addAll(List.of("-c", "SET bilpark.teslim_onay = 'EVET'"));
        }
        if (newAdminHash != null) {
            cmd.addAll(List.of("-c", "SET bilpark.yeni_admin_hash = '" + newAdminHash + "'"));
        }
        cmd.addAll(List.of("-f", "/tmp/teslim-sifirlama.sql"));
        return postgres.execInContainer(cmd.toArray(new String[0]));
    }

    @Test
    void scriptRefusesWithoutApprovalAndResetsWithIt() throws Exception {
        // the application's first start created admin, tevfik and the developer account
        seedDemoData();
        for (String t : OPERATIONAL) {
            assertTrue(count(t) > 0, t + " should hold demo data");
        }

        Map<String, String> kept = new LinkedHashMap<>();
        for (String t : KEPT) kept.put(t, fingerprint(t));
        kept.put("tariffs (non-draft)", fingerprint("(SELECT * FROM tariffs WHERE status <> 'DRAFT')"));
        kept.put("users (admin, developer)", fingerprint("(SELECT * FROM users WHERE role = 'DEVELOPER' OR username = 'admin')"));
        long tariffCount = count("tariffs");
        Map<String, Long> before = new LinkedHashMap<>();
        for (String t : OPERATIONAL) before.put(t, count(t));
        long usersBefore = count("users");

        // 1) without the confirmation line: error, nothing deleted
        ExecResult refused = runScript(false, null);
        assertNotEquals(0, refused.getExitCode());
        assertTrue(refused.getStderr().contains("onaylanmadi"), refused.getStderr());
        for (String t : OPERATIONAL) assertEquals(before.get(t), count(t), t + " must be untouched");
        assertEquals(usersBefore, count("users"));

        // 2) with the confirmation line
        ExecResult done = runScript(true, null);
        assertEquals(0, done.getExitCode(), done.getStderr());
        assertTrue(done.getStdout().contains("fiscal_years"), "the final SELECT shows the remaining counts");

        for (String t : List.of("parking_records", "active_park_spots", "check_in_requests", "shifts",
                "subscriptions", "sync_events", "error_logs")) {
            assertEquals(0, count(t), t);
        }
        assertEquals(1, count("audit_logs"));
        assertEquals(List.of("SETTINGS_UPDATED:system:Teslim sıfırlaması yapıldı: tanıtım kayıtları silindi, sistem boş teslim edildi."),
                jdbc.queryForList("SELECT action || ':' || actor_username || ':' || summary FROM audit_logs", String.class));
        assertEquals(List.of("admin:ADMIN", "gelistirici:DEVELOPER"),
                jdbc.queryForList("SELECT username || ':' || role FROM users ORDER BY username", String.class));

        for (String t : KEPT) assertEquals(kept.get(t), fingerprint(t), t + " must be unchanged");
        assertEquals(kept.get("tariffs (non-draft)"), fingerprint("(SELECT * FROM tariffs WHERE status <> 'DRAFT')"));
        assertEquals(kept.get("users (admin, developer)"),
                fingerprint("(SELECT * FROM users WHERE role = 'DEVELOPER' OR username = 'admin')"));
        assertEquals(tariffCount, count("tariffs"));
        assertNull(jdbc.queryForObject("SELECT created_by_id FROM tariffs WHERE status = 'DRAFT'", Long.class));
        assertEquals("demo2", jdbc.queryForObject("SELECT created_by_username FROM tariffs WHERE status = 'DRAFT'", String.class));

        // identity counters of the emptied tables restart; sync_events keeps counting
        jdbc.update("INSERT INTO parking_records (license_plate, debt_paid) VALUES ('34YENI1', true)");
        assertEquals(1L, jdbc.queryForObject("SELECT min(id) FROM parking_records", Long.class));
        jdbc.update("DELETE FROM parking_records");

        // 3) the application starts again on the reset database (a second, real application context)
        String[] args = {
                "--server.port=0",
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                "--spring.flyway.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate"
        };
        try (ConfigurableApplicationContext second = SpringApplication.run(BackendApplication.class, args)) {
            assertTrue(second.isRunning());
        }
        // the start did not re-seed anything: users still exist, so ADMIN_PASSWORD is not read again
        assertEquals(2, count("users"));
        assertEquals(1, count("audit_logs"));

        // 4) optional BCrypt hash for the admin: the script is safe to run again and replaces the hash
        String hash = new BCryptPasswordEncoder().encode("yeni-teslim-sifresi-1");
        ExecResult again = runScript(true, hash);
        assertEquals(0, again.getExitCode(), again.getStderr());
        assertEquals(hash, jdbc.queryForObject("SELECT password FROM users WHERE username = 'admin'", String.class));
        assertNotNull(jdbc.queryForObject("SELECT password_changed_at FROM users WHERE username = 'admin'", java.time.LocalDateTime.class));
        assertEquals(1, count("audit_logs"));
    }
}
