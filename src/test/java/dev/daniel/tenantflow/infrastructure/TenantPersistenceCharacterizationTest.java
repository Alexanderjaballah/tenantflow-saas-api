package dev.daniel.tenantflow.infrastructure;

import dev.daniel.tenantflow.application.CreateWorkOrderCommand;
import dev.daniel.tenantflow.application.WorkOrderNotFoundException;
import dev.daniel.tenantflow.application.WorkOrderService;
import dev.daniel.tenantflow.domain.WorkOrder;
import dev.daniel.tenantflow.domain.WorkOrderPriority;
import dev.daniel.tenantflow.domain.WorkOrderStatus;
import dev.daniel.tenantflow.infrastructure.persistence.JpaWorkOrderRepositoryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaWorkOrderRepositoryAdapter.class)
class TenantPersistenceCharacterizationTest {

    private static final Instant NOW = Instant.parse("2026-06-27T12:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withLabel("factory.mission", "de7ddd1d")
            .withLabel("factory.fixture", "tenant-persistence");

    @Autowired
    JpaWorkOrderRepositoryAdapter repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void keepsReachableFlowsTenantScopedAndVersionsSequential() {
        assertPostgres17();
        var tenant = new AtomicReference<>("acme");
        var service = new WorkOrderService(
                repository, tenant::get, Clock.fixed(NOW, ZoneOffset.UTC));

        var acme = service.create(new CreateWorkOrderCommand(
                "Sequential persistence", "preserved",
                WorkOrderPriority.HIGH, "owner@acme.test", NOW.plusSeconds(3600)));
        var globex = repository.save(WorkOrder.create(
                "globex", "Foreign persistence", null,
                WorkOrderPriority.LOW, null, null, NOW));

        assertEquals(0, acme.version());
        assertEquals("acme", sqlTenant(acme));
        assertTrue(service.findAll().stream().allMatch(row -> row.tenantId().equals("acme")));
        assertTrue(service.findAll().stream().noneMatch(row -> row.id().equals(globex.id())));

        tenant.set("globex");
        assertThrows(WorkOrderNotFoundException.class, () -> service.find(acme.id()));
        assertThrows(WorkOrderNotFoundException.class,
                () -> service.changeStatus(acme.id(), WorkOrderStatus.CANCELLED));
        assertEquals("acme", sqlTenant(acme));
        assertEquals(WorkOrderStatus.OPEN.name(), sqlStatus(acme));
        assertEquals(0L, sqlVersion(acme));

        tenant.set("acme");
        var inProgress = service.changeStatus(acme.id(), WorkOrderStatus.IN_PROGRESS);
        assertEquals(1, inProgress.version());
        assertEquals(WorkOrderStatus.IN_PROGRESS.name(), sqlStatus(acme));
        assertEquals(1L, sqlVersion(acme));

        var completed = service.changeStatus(acme.id(), WorkOrderStatus.COMPLETED);
        assertEquals(2, completed.version());
        assertEquals(WorkOrderStatus.COMPLETED.name(), sqlStatus(acme));
        assertEquals(2L, sqlVersion(acme));
        assertEquals("acme", sqlTenant(acme));
        assertEquals(acme.id(), completed.id());
        assertEquals(acme.title(), completed.title());
    }

    private void assertPostgres17() {
        var version = jdbc.queryForMap(
                "SELECT version() AS version, current_setting('server_version_num')::int AS number");
        assertTrue(version.get("version").toString().startsWith("PostgreSQL 17."));
        int number = ((Number) version.get("number")).intValue();
        assertTrue(number >= 170000 && number <= 179999);
    }

    private String sqlTenant(WorkOrder workOrder) {
        return jdbc.queryForObject(
                "SELECT tenant_id FROM work_orders WHERE id = ?",
                String.class, workOrder.id());
    }

    private String sqlStatus(WorkOrder workOrder) {
        return jdbc.queryForObject(
                "SELECT status FROM work_orders WHERE id = ?",
                String.class, workOrder.id());
    }

    private long sqlVersion(WorkOrder workOrder) {
        return jdbc.queryForObject(
                "SELECT version FROM work_orders WHERE id = ?",
                Long.class, workOrder.id());
    }
}
