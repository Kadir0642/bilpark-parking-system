package com.bilpark.backend.postgres;

import org.flywaydb.core.Flyway;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Flyway configured with the baseline settings of the real application.properties (not a copy of them). */
final class AppFlyway {

    private AppFlyway() {}

    static Flyway forUrl(String url, String user, String password) {
        Properties app = new Properties();
        try (InputStream in = AppFlyway.class.getResourceAsStream("/application.properties")) {
            app.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(Boolean.parseBoolean(app.getProperty("spring.flyway.baseline-on-migrate")))
                .baselineVersion(app.getProperty("spring.flyway.baseline-version"))
                .load();
    }

    /**
     * Columns, constraints and indexes of the public schema as sorted text lines. Primary key / unique
     * constraint and index names are left out (Hibernate gave them random names on the live database).
     */
    static List<String> schemaFingerprint(Connection c) throws SQLException {
        String sql = """
                SELECT 'col ' || table_name || '.' || column_name || ' ' || data_type
                       || coalesce('(' || character_maximum_length || ')', '') || ' null=' || is_nullable
                       || ' def=' || coalesce(column_default, '-') || ' id=' || is_identity
                  FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'
                UNION ALL
                SELECT 'con ' || t.relname || ' ' || c.contype::text || ' '
                       || CASE WHEN c.contype IN ('p', 'u') THEN pg_get_constraintdef(c.oid)
                               ELSE c.conname || ' ' || pg_get_constraintdef(c.oid) END
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = current_schema() AND t.relname <> 'flyway_schema_history'
                UNION ALL
                SELECT 'idx ' || tablename || ' ' || regexp_replace(indexdef, 'INDEX \\S+ ON', 'INDEX ON')
                  FROM pg_indexes
                 WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history'
                ORDER BY 1
                """;
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
