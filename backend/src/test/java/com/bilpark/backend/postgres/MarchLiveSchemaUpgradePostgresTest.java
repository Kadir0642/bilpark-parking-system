package com.bilpark.backend.postgres;

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
 * The live Neon database still has the March 2026 schema (commit 6abc999: two tables, no Flyway).
 * Upgrades a copy of it with the application's own Flyway settings and checks that every row is kept
 * and that the result has exactly the schema an empty database gets.
 */
@Testcontainers(disabledWithoutDocker = true)
class MarchLiveSchemaUpgradePostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static String urlFor(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + db;
    }

    private static Connection conn(String url) throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    private static MigrateResult migrate(String url) {
        return AppFlyway.forUrl(url, postgres.getUsername(), postgres.getPassword()).migrate();
    }

    private static String createDatabase(String name) throws SQLException {
        try (Connection c = conn(postgres.getJdbcUrl()); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return urlFor(name);
    }

    private static String marchSql() throws Exception {
        try (var in = MarchLiveSchemaUpgradePostgresTest.class.getResourceAsStream("/legacy/march-2026-live-schema.sql")) {
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
    void marchSchemaUpgradesToLatestAndKeepsEveryRow() throws Exception {
        String url = createDatabase("march_live");
        try (Connection c = conn(url); Statement st = c.createStatement()) {
            st.execute(marchSql());
        }

        MigrateResult result = migrate(url);
        assertTrue(result.success);

        try (Connection c = conn(url); Statement st = c.createStatement()) {
            // baselined below V1, so the idempotent V1 creates users, zones, subscriptions and fiscal_years
            assertEquals(List.of("BASELINE:0", "SQL:1", "SQL:2", "SQL:3", "SQL:4", "SQL:5", "SQL:6", "SQL:7"),
                    strings(c, "SELECT type || ':' || version FROM flyway_schema_history ORDER BY installed_rank"));
            assertEquals(0, count(c, "SELECT count(*) FROM flyway_schema_history WHERE NOT success"));

            // active vehicles: same ids, times, types and streets; plates normalized by V2;
            // version starts at 0 (optimistic lock), zone stays empty (the March app had no zones)
            assertEquals(List.of(
                            "2|11BK456|LARGE|TEVFIK_BEY|2026-03-20 09:10:00|RIGHT|ACTIVE|0|-",
                            "3|34XYZ789|SMALL|ALI_RIZA_OZKAY|2026-03-20 09:25:00|LEFT|ACTIVE|0|-",
                            "4|11DE202|SMALL|CUMHURIYET|2026-03-20 10:02:00|RIGHT|ACTIVE|0|-",
                            "9|11PR707|SMALL|CUMHURIYET|2026-03-20 10:40:00|RIGHT|ACTIVE|0|-",
                            "10|11HJ404|SMALL|TEVFIK_BEY|2026-03-20 11:05:00|LEFT|ACTIVE|0|-"),
                    strings(c, "SELECT concat_ws('|', id, current_plate, current_type, street, entry_time, side, status, "
                            + "version, coalesce(zone_id::text, '-')) FROM active_park_spots ORDER BY id"));

            // closed records: fees and times unchanged; RUNAWAY debt is unpaid, the rest is paid;
            // fields that did not exist in March stay empty (no guessing)
            assertEquals(List.of(
                            "1|11ABC123|PAID|40|true|SMALL|2026-03-12 09:14:00|2026-03-12 11:02:00",
                            "2|06FG303|PAID|50|true|LARGE|2026-03-12 13:30:00|2026-03-12 14:10:00",
                            "3|16KL505|PAID|25|true|SMALL|2026-03-14 08:05:00|2026-03-14 08:47:00",
                            "4|11HJ404|RUNAWAY|70|false|SMALL|2026-03-15 10:00:00|2026-03-15 13:20:00",
                            "5|11MN606|RUNAWAY|80|false|LARGE|2026-03-18 16:40:00|2026-03-18 18:05:00"),
                    strings(c, "SELECT concat_ws('|', id, license_plate, status, fee, debt_paid::text, vehicle_type, "
                            + "entry_time, exit_time) FROM parking_records ORDER BY id"));
            assertEquals(5, count(c, "SELECT count(*) FROM parking_records WHERE zone_id IS NULL AND payment_method IS NULL "
                    + "AND collected_by_id IS NULL AND tariff_id IS NULL AND paid_minutes IS NULL "
                    + "AND cancelled_at IS NULL AND NOT plate_anonymized"));
            assertEquals(150, count(c, "SELECT sum(fee) FROM parking_records WHERE NOT debt_paid"));

            // identity sequences continue after the March rows
            st.execute("INSERT INTO active_park_spots(current_plate, street, version) VALUES ('11YENI1', 'CUMHURIYET', 0)");
            assertEquals(11, count(c, "SELECT id FROM active_park_spots WHERE current_plate = '11YENI1'"));
            st.execute("INSERT INTO parking_records(license_plate, status, fee, debt_paid) VALUES ('11YENI1', 'PAID', 0, true)");
            assertEquals(6, count(c, "SELECT id FROM parking_records WHERE license_plate = '11YENI1'"));

            // the tables the March database never had now exist and are seeded by V3-V5
            assertEquals(6, count(c, "SELECT count(*) FROM zones"));
            assertEquals(3, count(c, "SELECT count(*) FROM streets"));
            assertEquals(1, count(c, "SELECT count(*) FROM tariffs WHERE status = 'ACTIVE'"));
            assertEquals(0, count(c, "SELECT count(*) FROM users"));
            assertEquals(0, count(c, "SELECT count(*) FROM subscriptions"));

            // Hibernate's March CHECK constraints are gone: a new status / street code is not blocked
            assertEquals(0, count(c, "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
                    + "WHERE t.relname IN ('active_park_spots', 'parking_records') AND c.contype = 'c'"));
            // the random unique name is kept and still protects the active plate
            assertEquals(1, count(c, "SELECT count(*) FROM pg_constraint WHERE conname = 'uk8nyd0a8o0joqc7aaa31r3xeoo'"));
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO active_park_spots(current_plate, street, version) VALUES ('11BK456', 'TEVFIK_BEY', 0)"));
        }

        // same schema as a database built from nothing
        String freshUrl = createDatabase("fresh_for_compare");
        assertTrue(migrate(freshUrl).success);
        try (Connection upgraded = conn(url); Connection fresh = conn(freshUrl)) {
            assertEquals(AppFlyway.schemaFingerprint(fresh), AppFlyway.schemaFingerprint(upgraded));
        }

        // a restart is a no-op
        assertEquals(0, migrate(url).migrationsExecuted);
    }

    @Test
    void legacyColumnsAreNotOverwrittenWhenTheyAlreadyExist() throws Exception {
        // A database where Hibernate (stage 1, commit 964ce7e) already added debt_paid with real values
        String url = createDatabase("march_with_debt_paid");
        try (Connection c = conn(url); Statement st = c.createStatement()) {
            st.execute(marchSql());
            st.execute("ALTER TABLE parking_records ADD COLUMN debt_paid boolean");
            st.execute("UPDATE parking_records SET debt_paid = (status <> 'RUNAWAY')");
            st.execute("UPDATE parking_records SET debt_paid = true WHERE id = 4"); // debt collected later
            st.execute("ALTER TABLE parking_records ALTER COLUMN debt_paid SET NOT NULL");
        }

        assertTrue(migrate(url).success);

        try (Connection c = conn(url)) {
            assertEquals(List.of("1:true", "2:true", "3:true", "4:true", "5:false"),
                    strings(c, "SELECT id || ':' || debt_paid FROM parking_records ORDER BY id"));
        }
    }
}
