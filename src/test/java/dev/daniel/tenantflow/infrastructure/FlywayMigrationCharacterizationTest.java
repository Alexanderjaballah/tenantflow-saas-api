package dev.daniel.tenantflow.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(Lifecycle.PER_CLASS)
class FlywayMigrationCharacterizationTest {

    private static final String IMAGE = "postgres:17-alpine";
    private static final String V1_SHA_256 =
            "73e830c03f131def50bdfdff708f483c9d76de2042928467dc19bc74f20ec5a6";

    @Test
    void appliesV1OnceAndMatchesTheSemanticPostgres17Schema() throws Exception {
        try (var postgres = postgres("flyway-schema")) {
            postgres.start();
            try (var connection = connection(postgres)) {
                assertPostgres17(connection);
            }

            var flyway = flyway(postgres);
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(V1_SHA_256, v1Sha256());

            try (var connection = connection(postgres)) {
                assertEquals(List.of(
                                new Column(1, "id", "uuid", true, ""),
                                new Column(2, "tenant_id", "character varying(80)", true, ""),
                                new Column(3, "title", "character varying(160)", true, ""),
                                new Column(4, "description", "text", false, ""),
                                new Column(5, "priority", "character varying(20)", true, ""),
                                new Column(6, "status", "character varying(30)", true, ""),
                                new Column(7, "assignee_email", "character varying(254)", false, ""),
                                new Column(8, "due_at", "timestamp with time zone", false, ""),
                                new Column(9, "created_at", "timestamp with time zone", true, ""),
                                new Column(10, "updated_at", "timestamp with time zone", true, ""),
                                new Column(11, "version", "bigint", true, "0")),
                        columns(connection));
                assertEquals("PRIMARY KEY (id)", scalar(connection, """
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conrelid = 'public.work_orders'::regclass AND contype = 'p'
                        """));
                assertEquals(List.of(
                                "idx_work_orders_tenant_created|tenant_id ASC NULLS LAST,created_at DESC NULLS FIRST",
                                "idx_work_orders_tenant_status|tenant_id ASC NULLS LAST,status ASC NULLS LAST"),
                        secondaryIndexes(connection));

                var history = history(connection);
                assertEquals(1, history.size());
                assertEquals("1", history.get(0).version());
                assertEquals("create work orders", history.get(0).description());
                assertEquals("V1__create_work_orders.sql", history.get(0).script());
                assertTrue(history.get(0).success());
                assertNotNull(history.get(0).checksum());
            }
        }
    }

    @Test
    void rerunValidatesStableHistoryAndPreservesExistingRows() throws Exception {
        try (var postgres = postgres("flyway-restart")) {
            postgres.start();
            var flyway = flyway(postgres);
            assertEquals(1, flyway.migrate().migrationsExecuted);

            UUID id = UUID.fromString("40000000-0000-0000-0000-000000000001");
            try (var connection = connection(postgres);
                 var statement = connection.prepareStatement("""
                         INSERT INTO work_orders (
                             id, tenant_id, title, priority, status, created_at, updated_at
                         ) VALUES (?, 'acme', 'Existing row', 'HIGH', 'OPEN',
                                   '2026-06-27T12:00:00Z', '2026-06-27T12:00:00Z')
                         """)) {
                assertPostgres17(connection);
                statement.setObject(1, id);
                assertEquals(1, statement.executeUpdate());
            }

            List<HistoryRow> before;
            String rowBefore;
            try (var connection = connection(postgres)) {
                before = history(connection);
                rowBefore = existingRow(connection, id);
            }

            assertEquals(0, flyway.migrate().migrationsExecuted);
            flyway.validate();

            try (var connection = connection(postgres)) {
                assertEquals(before, history(connection));
                assertEquals(rowBefore, existingRow(connection, id));
                assertEquals(1L, scalarLong(connection,
                        "SELECT count(*) FROM flyway_schema_history WHERE success"));
            }
        }
    }

    private static PostgreSQLContainer<?> postgres(String fixture) {
        return new PostgreSQLContainer<>(IMAGE)
                .withLabel("factory.mission", "de7ddd1d")
                .withLabel("factory.fixture", fixture);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();
    }

    private static Connection connection(PostgreSQLContainer<?> postgres) throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void assertPostgres17(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT version(), current_setting('server_version_num')::int")) {
            assertTrue(result.next());
            assertTrue(result.getString(1).startsWith("PostgreSQL 17."));
            assertTrue(result.getInt(2) >= 170000 && result.getInt(2) <= 179999);
        }
    }

    private static List<Column> columns(Connection connection) throws Exception {
        var columns = new ArrayList<Column>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT a.attnum, a.attname,
                            format_type(a.atttypid, a.atttypmod),
                            a.attnotnull,
                            COALESCE(pg_get_expr(d.adbin, d.adrelid), '')
                     FROM pg_attribute a
                     JOIN pg_class c ON c.oid = a.attrelid
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                     LEFT JOIN pg_attrdef d
                       ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                     WHERE n.nspname = 'public' AND c.relname = 'work_orders'
                       AND a.attnum > 0 AND NOT a.attisdropped
                     ORDER BY a.attnum
                     """)) {
            while (result.next()) {
                columns.add(new Column(
                        result.getInt(1), result.getString(2), result.getString(3),
                        result.getBoolean(4), result.getString(5)));
            }
        }
        return columns;
    }

    private static List<String> secondaryIndexes(Connection connection) throws Exception {
        var indexes = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT ci.relname,
                            string_agg(
                              a.attname || ' ' ||
                              CASE WHEN (i.indoption[k.ordinality - 1] & 1) = 1
                                   THEN 'DESC' ELSE 'ASC' END || ' ' ||
                              CASE WHEN (i.indoption[k.ordinality - 1] & 2) = 2
                                   THEN 'NULLS FIRST' ELSE 'NULLS LAST' END,
                              ',' ORDER BY k.ordinality)
                     FROM pg_index i
                     JOIN pg_class ct ON ct.oid = i.indrelid
                     JOIN pg_namespace n ON n.oid = ct.relnamespace
                     JOIN pg_class ci ON ci.oid = i.indexrelid
                     CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ordinality)
                     JOIN pg_attribute a ON a.attrelid = ct.oid AND a.attnum = k.attnum
                     WHERE n.nspname = 'public' AND ct.relname = 'work_orders'
                       AND NOT i.indisprimary
                     GROUP BY ci.relname
                     ORDER BY ci.relname
                     """)) {
            while (result.next()) {
                indexes.add(result.getString(1) + "|" + result.getString(2));
            }
        }
        return indexes;
    }

    private static List<HistoryRow> history(Connection connection) throws Exception {
        var rows = new ArrayList<HistoryRow>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT installed_rank, version, description, type, script,
                            checksum, installed_on::text, success
                     FROM flyway_schema_history
                     ORDER BY installed_rank
                     """)) {
            while (result.next()) {
                rows.add(new HistoryRow(
                        result.getInt(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getInt(6),
                        result.getString(7), result.getBoolean(8)));
            }
        }
        return rows;
    }

    private static String existingRow(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT id, tenant_id, title, priority, status, version,
                       created_at::text, updated_at::text
                FROM work_orders WHERE id = ?
                """)) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return String.join("|",
                        result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6),
                        result.getString(7), result.getString(8));
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String v1Sha256() throws Exception {
        try (InputStream stream = FlywayMigrationCharacterizationTest.class.getResourceAsStream(
                "/db/migration/V1__create_work_orders.sql")) {
            assertNotNull(stream);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }

    private record Column(int ordinal, String name, String type, boolean notNull, String defaultValue) {
    }

    private record HistoryRow(
            int installedRank,
            String version,
            String description,
            String type,
            String script,
            int checksum,
            String installedOn,
            boolean success) {
    }
}
