package com.bilpark.backend.postgres;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates the LIVE database (created by Hibernate ddl-auto=update before Flyway existed)
 * and runs the migrations with baseline-on-migrate, then checks an empty database too.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayBaselinePostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static Flyway flyway(String url) {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    private static Connection conn(String url) throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    private static String urlFor(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + db;
    }

    private static String v1Sql() throws Exception {
        try (var in = FlywayBaselinePostgresTest.class.getResourceAsStream("/db/migration/V1__baseline.sql")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void liveDatabaseIsBaselinedThenV2Applied() throws Exception {
        String url = postgres.getJdbcUrl();
        try (Connection c = conn(url); Statement st = c.createStatement()) {
            // pre-stage-2 schema as Hibernate created it, with a random unique constraint name
            st.execute(v1Sql().replace("uk_subscriptions_license_plate", "ukabc123xyz"));
            st.execute("INSERT INTO zones(street, zone_number, zone_name) VALUES ('TEVFIK_BEY', 1, 'Z1')");
            st.execute("INSERT INTO users(username, password, role) VALUES ('admin','x','ADMIN')");
            // Hibernate-style check constraints that would block the new DEVELOPER role / CANCELLED status
            st.execute("ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN','OFFICER'))");
            st.execute("ALTER TABLE parking_records ADD CONSTRAINT parking_records_status_check "
                    + "CHECK (status IN ('ACTIVE','PAID','RUNAWAY'))");
            st.execute("INSERT INTO parking_records(license_plate, debt_paid, status, fee) VALUES ('34 abc-123', true, 'PAID', 20)");
            st.execute("INSERT INTO active_park_spots(current_plate, street) VALUES ('06 a 12345', 'TEVFIK_BEY')");
            st.execute("INSERT INTO subscriptions(license_plate, vehicle_type, start_date, end_date, status) "
                    + "VALUES ('34 abc 123', 'SMALL', '2026-01-01', '2099-01-01', 'ACTIVE')");
            st.execute("INSERT INTO subscriptions(license_plate, vehicle_type, start_date, end_date, status) "
                    + "VALUES ('35XY999', 'SMALL', '2025-01-01', '2025-02-01', 'EXPIRED')");
            st.execute("INSERT INTO fiscal_years(start_date, end_date, label, is_active) VALUES ('2026-01-01','2026-12-31','2026',true)");
        }

        MigrateResult result = flyway(url).migrate();
        assertTrue(result.success);
        assertEquals(4, result.migrationsExecuted, "V2, V3, V4 and V5 should run");

        try (Connection c = conn(url); Statement st = c.createStatement()) {
            List<String> hist = strings(c,
                    "SELECT type || ':' || version FROM flyway_schema_history ORDER BY installed_rank");
            assertEquals(List.of("BASELINE:1", "SQL:2", "SQL:3", "SQL:4", "SQL:5"), hist);

            assertEquals(0, count(c, "SELECT count(*) FROM pg_constraint WHERE conname = 'ukabc123xyz'"));
            assertEquals(1, count(c, "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_subscriptions_active_plate'"));

            assertEquals("34ABC123", strings(c, "SELECT license_plate FROM parking_records").get(0));
            assertEquals("06A12345", strings(c, "SELECT current_plate FROM active_park_spots").get(0));
            assertEquals(List.of("34ABC123", "35XY999"),
                    strings(c, "SELECT license_plate FROM subscriptions ORDER BY id"));

            // old non-ACTIVE rows for the same plate are now allowed
            st.execute("INSERT INTO subscriptions(license_plate, vehicle_type, start_date, end_date, status) "
                    + "VALUES ('35XY999', 'SMALL', '2025-03-01', '2025-04-01', 'CANCELLED')");
            // second ACTIVE row for the same plate is rejected
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO subscriptions(license_plate, vehicle_type, start_date, end_date, status) "
                            + "VALUES ('34ABC123', 'SMALL', '2026-02-01', '2026-03-01', 'ACTIVE')"));

            // new columns / tables
            assertEquals(4, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name='parking_records' "
                    + "AND column_name IN ('payment_method','collected_by_id','collected_by_username','device_id')"));
            assertEquals(2, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name='fiscal_years' "
                    + "AND column_name IN ('archived','archived_at')"));
            assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='shifts'"));
            assertEquals(1, count(c, "SELECT count(*) FROM pg_indexes WHERE indexname='ix_shifts_user_device_open'"));
            assertFalse(strings(c, "SELECT archived::text FROM fiscal_years").contains("true"));
            assertEquals(1, count(c, "SELECT count(*) FROM parking_records WHERE payment_method IS NULL"));

            // ---- V3 ----
            assertEquals(3, count(c, "SELECT count(*) FROM streets"));
            assertEquals("Z1", strings(c, "SELECT zone_name FROM zones WHERE street='TEVFIK_BEY' AND zone_number=1").get(0));
            assertEquals(6, count(c, "SELECT count(*) FROM zones"));
            assertEquals(1, count(c, "SELECT count(*) FROM pg_constraint WHERE conname='fk_zones_street'"));
            assertEquals(1, count(c, "SELECT count(*) FROM tariffs WHERE status='ACTIVE'"));
            // the V3 seed prices now live in tariff_vehicle_prices (moved by V5)
            assertEquals(1, count(c, "SELECT count(*) FROM tariffs WHERE status='ACTIVE' AND free_minutes=5 "
                    + "AND paid_start='08:00' AND paid_end='22:00'"));
            assertEquals(2, count(c, "SELECT count(*) FROM tariff_vehicle_prices p JOIN tariffs t ON t.id = p.tariff_id "
                    + "WHERE t.status='ACTIVE' AND p.enabled AND ("
                    + "(p.vehicle_type='SMALL' AND p.first_hour=25 AND p.extra_hour=15 AND p.subscription_monthly=2500) OR "
                    + "(p.vehicle_type='LARGE' AND p.first_hour=50 AND p.extra_hour=30 AND p.subscription_monthly=3000))"));
            assertEquals(2, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name='parking_records' "
                    + "AND column_name IN ('tariff_id','paid_minutes')"));
            assertEquals(1, count(c, "SELECT count(*) FROM parking_records WHERE tariff_id IS NULL AND paid_minutes IS NULL"));
            assertEquals(1, count(c, "SELECT count(*) FROM users WHERE username='admin' AND active"));
            assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='sync_events'"));
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO tariffs (status, free_minutes, paid_start, paid_end, created_by_username, created_at) "
                            + "VALUES ('ACTIVE',5,'08:00','22:00','t',now())"));

            // ---- V4 ----
            assertEquals(3, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name='users' "
                    + "AND column_name IN ('full_name','created_at','password_changed_at')"));
            assertEquals(5, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name='parking_records' "
                    + "AND column_name IN ('cancelled_from_status','cancelled_at','cancelled_by_username',"
                    + "'cancel_reason','plate_anonymized')"));
            // existing rows get plate_anonymized = false
            assertEquals(1, count(c, "SELECT count(*) FROM parking_records WHERE plate_anonymized = false "
                    + "AND cancelled_at IS NULL AND cancelled_from_status IS NULL"));
            assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='audit_logs'"));
            assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='app_settings'"));
            assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='error_logs'"));
            assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='license_info'"));
            assertEquals(1, count(c, "SELECT count(*) FROM license_info"));
            assertEquals(1, count(c, "SELECT count(*) FROM license_info WHERE id = 1 AND grace_days = 15 "
                    + "AND expires_on BETWEEN current_date + 364 AND current_date + 366"));
            assertEquals(3, count(c, "SELECT count(*) FROM pg_indexes WHERE indexname IN "
                    + "('ix_audit_logs_created_at','ix_audit_logs_action','ix_audit_logs_actor')"));

            // the old role / status check constraints are gone
            assertEquals(0, count(c, "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                    + "WHERE t.relname='users' AND c.contype='c'"));
            assertEquals(0, count(c, "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                    + "WHERE t.relname='parking_records' AND c.contype='c' AND pg_get_constraintdef(c.oid) ILIKE '%status%'"));
            st.execute("INSERT INTO users(username, password, role) VALUES ('gelistirici','x','DEVELOPER')");
            st.execute("INSERT INTO parking_records(license_plate, debt_paid, status, fee, plate_anonymized, cancelled_from_status) "
                    + "VALUES ('06C1', true, 'CANCELLED', 0, false, 'ACTIVE')");
            assertEquals(1, count(c, "SELECT count(*) FROM users WHERE role='DEVELOPER'"));

            // audit_logs accepts a row (identity column, nullable actor)
            st.execute("INSERT INTO audit_logs(created_at, actor_username, action, summary) "
                    + "VALUES (now(), 'system', 'RETENTION_RUN', 'x')");
            assertEquals(1, count(c, "SELECT count(*) FROM audit_logs"));
        }

        try (Connection c = conn(url); Statement st = c.createStatement()) {
            // ---- V5 ----
            assertEquals(List.of("SMALL:true", "LARGE:true", "MOTORCYCLE:false"),
                    strings(c, "SELECT code || ':' || active FROM vehicle_types ORDER BY sort_order"));
            // the seeded V3 tariff (25/15/50/30, 2500/3000) moved into rows
            assertEquals(List.of("LARGE:true:50:30:3000", "SMALL:true:25:15:2500"),
                    strings(c, "SELECT vehicle_type || ':' || enabled || ':' || first_hour || ':' || extra_hour || ':' "
                            + "|| subscription_monthly FROM tariff_vehicle_prices ORDER BY vehicle_type"));
            assertEquals(0, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name='tariffs' "
                    + "AND column_name IN ('small_first_hour','small_extra_hour','large_first_hour','large_extra_hour',"
                    + "'subscription_small_monthly','subscription_large_monthly')"));
            assertEquals(3, count(c, "SELECT count(*) FROM pg_constraint WHERE contype='f' AND conname IN "
                    + "('fk_active_park_spots_vehicle_type','fk_parking_records_vehicle_type','fk_subscriptions_vehicle_type')"));
            // unknown codes are rejected by the FK, MOTORCYCLE is accepted
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO active_park_spots(current_plate, street, current_type) VALUES ('06ZZ1', 'TEVFIK_BEY', 'BUS')"));
        }

        // idempotent: second migrate is a no-op
        assertEquals(0, flyway(url).migrate().migrationsExecuted);
    }

    @Test
    void collidingActivePlatesStopV2AndLeaveDatabaseUntouched() throws Exception {
        try (Connection c = conn(postgres.getJdbcUrl()); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE conflict_db");
        }
        String url = urlFor("conflict_db");
        try (Connection c = conn(url); Statement st = c.createStatement()) {
            st.execute(v1Sql().replace("uk_subscriptions_license_plate", "ukdef456uvw"));
            // same vehicle recorded twice before server-side normalization existed
            st.execute("INSERT INTO active_park_spots(current_plate, street) VALUES ('34 ABC 123', 'TEVFIK_BEY')");
            st.execute("INSERT INTO active_park_spots(current_plate, street) VALUES ('34ABC123', 'TEVFIK_BEY')");
            st.execute("INSERT INTO parking_records(license_plate, debt_paid, status, fee) VALUES ('06 xy 999', true, 'PAID', 20)");
        }

        var ex = assertThrows(org.flywaydb.core.api.FlywayException.class, () -> flyway(url).migrate());
        assertTrue(ex.getMessage().contains("34ABC123"), ex.getMessage());

        try (Connection c = conn(url)) {
            // V2 rolled back as a whole: plates, constraint and columns unchanged
            assertEquals(List.of("34 ABC 123", "34ABC123"),
                    strings(c, "SELECT current_plate FROM active_park_spots ORDER BY id"));
            assertEquals("06 xy 999", strings(c, "SELECT license_plate FROM parking_records").get(0));
            assertEquals(1, count(c, "SELECT count(*) FROM pg_constraint WHERE conname = 'ukdef456uvw'"));
            assertEquals(0, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='shifts'"));
            assertEquals(0, count(c, "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success"));
        }

        // after the duplicate is resolved by hand, the migration goes through
        try (Connection c = conn(url); Statement st = c.createStatement()) {
            st.execute("DELETE FROM active_park_spots WHERE current_plate = '34 ABC 123'");
            st.execute("DELETE FROM flyway_schema_history WHERE NOT success");
        }
        assertTrue(flyway(url).migrate().success);
    }

    @Test
    void emptyDatabaseMigratesFromV1() throws Exception {
        try (Connection c = conn(postgres.getJdbcUrl()); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE fresh_db");
        }
        String url = urlFor("fresh_db");
        MigrateResult result = flyway(url).migrate();
        assertTrue(result.success);
        assertEquals(5, result.migrationsExecuted);

        try (Connection c = conn(url); Statement st = c.createStatement()) {
            assertEquals(List.of("SQL:1", "SQL:2", "SQL:3", "SQL:4", "SQL:5"),
                    strings(c, "SELECT type || ':' || version FROM flyway_schema_history ORDER BY installed_rank"));
            assertEquals(0, count(c, "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                    + "WHERE t.relname='subscriptions' AND c.contype='u'"));
            assertEquals(1, count(c, "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_subscriptions_active_plate'"));
            assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='shifts'"));
            assertEquals(1, count(c, "SELECT count(*) FROM license_info WHERE grace_days = 15"));
            assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_name='audit_logs'"));
        }
    }
}
