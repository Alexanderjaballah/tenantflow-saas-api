package dev.daniel.tenantflow.infrastructure;

import org.flywaydb.core.Flyway;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HibernateValidateOnlyCharacterizationTest {

    private static final String IMAGE = "postgres:17-alpine";

    @Test
    void validatesTheFlywaySchemaWithoutChangingIt() throws Exception {
        try (var postgres = postgres("hibernate-valid-schema")) {
            postgres.start();
            assertPostgres17(postgres);
            migrate(postgres);
            String before = schemaFingerprint(postgres);

            validate(postgres);

            assertEquals(before, schemaFingerprint(postgres));
        }
    }

    @Test
    void rejectsAMissingRequiredColumnInADedicatedDisposableFixture() throws Exception {
        var postgres = postgres("hibernate-missing-column-negative");
        try {
            postgres.start();
            assertPostgres17(postgres);
            migrate(postgres);
            try (var connection = connection(postgres);
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE work_orders DROP COLUMN title");
            }

            var failure = assertThrows(RuntimeException.class, () -> validate(postgres));
            assertTrue(hasCause(failure, SchemaManagementException.class));
            assertTrue(rootMessage(failure).contains("missing column [title]"));
        } finally {
            postgres.stop();
        }
        assertFalse(postgres.isRunning());
    }

    private static PostgreSQLContainer<?> postgres(String fixture) {
        return new PostgreSQLContainer<>(IMAGE)
                .withLabel("factory.mission", "de7ddd1d")
                .withLabel("factory.fixture", fixture)
                .withLabel("factory.disposable", "true");
    }

    private static void migrate(PostgreSQLContainer<?> postgres) {
        assertEquals(1, Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate()
                .migrationsExecuted);
    }

    private static void validate(PostgreSQLContainer<?> postgres) {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("dev.daniel.tenantflow.infrastructure.persistence");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        var properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.jdbc.time_zone", "UTC");
        factory.setJpaProperties(properties);
        try {
            factory.afterPropertiesSet();
            factory.getObject();
        } finally {
            factory.destroy();
        }
    }

    private static void assertPostgres17(PostgreSQLContainer<?> postgres) throws Exception {
        try (var connection = connection(postgres);
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT version(), current_setting('server_version_num')::int")) {
            assertTrue(result.next());
            assertTrue(result.getString(1).startsWith("PostgreSQL 17."));
            assertTrue(result.getInt(2) >= 170000 && result.getInt(2) <= 179999);
        }
    }

    private static String schemaFingerprint(PostgreSQLContainer<?> postgres) throws Exception {
        try (var connection = connection(postgres);
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT md5(string_agg(definition, E'\\n' ORDER BY definition))
                     FROM (
                       SELECT a.attnum || '|' || a.attname || '|' ||
                              format_type(a.atttypid, a.atttypmod) || '|' ||
                              a.attnotnull || '|' ||
                              COALESCE(pg_get_expr(d.adbin, d.adrelid), '') AS definition
                       FROM pg_attribute a
                       JOIN pg_class c ON c.oid = a.attrelid
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                       LEFT JOIN pg_attrdef d
                         ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                       WHERE n.nspname = 'public' AND c.relname = 'work_orders'
                         AND a.attnum > 0 AND NOT a.attisdropped
                       UNION ALL
                       SELECT indexdef
                       FROM pg_indexes
                       WHERE schemaname = 'public' AND tablename = 'work_orders'
                     ) schema_definition
                     """)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static Connection connection(PostgreSQLContainer<?> postgres) throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
