package dev.daniel.tenantflow.infrastructure;

import dev.daniel.tenantflow.api.ApiExceptionHandler;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaWorkOrderRepositoryAdapter.class)
class OptimisticLockingCharacterizationTest {

    private static final Instant NOW = Instant.parse("2026-06-27T12:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withLabel("factory.mission", "de7ddd1d")
            .withLabel("factory.fixture", "optimistic-locking");

    @Autowired
    JpaWorkOrderRepositoryAdapter repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsTheStaleOfTwoConcurrentUpdatesAndMapsTheConflict() throws Exception {
        assertPostgres17();
        var initial = repository.save(WorkOrder.create(
                "acme", "Concurrent update", "original",
                WorkOrderPriority.HIGH, null, null, NOW));
        assertEquals(0, initial.version());

        var bothLoaded = new CountDownLatch(2);
        var releaseCommits = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<Outcome> outcomes = new ArrayList<>();
        try {
            var first = executor.submit(() -> update(
                    initial, WorkOrderStatus.IN_PROGRESS, NOW.plusSeconds(60),
                    bothLoaded, releaseCommits));
            var second = executor.submit(() -> update(
                    initial, WorkOrderStatus.CANCELLED, NOW.plusSeconds(120),
                    bothLoaded, releaseCommits));

            assertTrue(bothLoaded.await(10, TimeUnit.SECONDS));
            releaseCommits.countDown();
            outcomes.add(first.get(20, TimeUnit.SECONDS));
            outcomes.add(second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseCommits.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        var winners = outcomes.stream().filter(outcome -> outcome.failure() == null).toList();
        var losers = outcomes.stream().filter(outcome -> outcome.failure() != null).toList();
        assertEquals(1, winners.size());
        assertEquals(1, losers.size());
        var staleFailure = optimisticLockingFailure(losers.get(0).failure());
        assertNotNull(staleFailure);
        assertInstanceOf(OptimisticLockingFailureException.class, staleFailure);

        var winner = winners.get(0).saved();
        var persisted = repository.findByIdAndTenantId(initial.id(), "acme").orElseThrow();
        assertEquals(1, persisted.version());
        assertEquals(winner.status(), persisted.status());
        assertEquals(winner.updatedAt(), persisted.updatedAt());
        assertEquals("original", persisted.description());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM work_orders WHERE id = ?", Long.class, initial.id()));

        standaloneSetup(new ConflictProbeController(staleFailure))
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Work order conflict"));
    }

    private Outcome update(
            WorkOrder initial,
            WorkOrderStatus status,
            Instant updatedAt,
            CountDownLatch bothLoaded,
            CountDownLatch releaseCommits) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            var saved = transaction.execute(ignored -> {
                var loaded = repository.findByIdAndTenantId(initial.id(), "acme").orElseThrow();
                assertEquals(0, loaded.version());
                bothLoaded.countDown();
                await(releaseCommits);
                return repository.save(loaded.transitionTo(status, updatedAt));
            });
            return new Outcome(saved, null);
        } catch (RuntimeException failure) {
            return new Outcome(null, failure);
        }
    }

    private void assertPostgres17() {
        var version = jdbc.queryForMap(
                "SELECT version() AS version, current_setting('server_version_num')::int AS number");
        assertTrue(version.get("version").toString().startsWith("PostgreSQL 17."));
        int number = ((Number) version.get("number")).intValue();
        assertTrue(number >= 170000 && number <= 179999);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static OptimisticLockingFailureException optimisticLockingFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof OptimisticLockingFailureException optimistic) {
                return optimistic;
            }
        }
        return null;
    }

    private record Outcome(WorkOrder saved, RuntimeException failure) {
    }

    @RestController
    private static final class ConflictProbeController {
        private final OptimisticLockingFailureException failure;

        private ConflictProbeController(OptimisticLockingFailureException failure) {
            this.failure = failure;
        }

        @GetMapping("/conflict")
        void conflict() {
            throw failure;
        }
    }
}
