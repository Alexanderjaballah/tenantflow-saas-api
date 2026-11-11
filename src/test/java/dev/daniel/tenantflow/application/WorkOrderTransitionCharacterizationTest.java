package dev.daniel.tenantflow.application;

import dev.daniel.tenantflow.api.WorkOrderController;
import dev.daniel.tenantflow.application.port.TenantContext;
import dev.daniel.tenantflow.application.port.WorkOrderRepository;
import dev.daniel.tenantflow.domain.WorkOrder;
import dev.daniel.tenantflow.domain.WorkOrderPriority;
import dev.daniel.tenantflow.domain.WorkOrderStatus;
import dev.daniel.tenantflow.infrastructure.security.JwtTenantContext;
import dev.daniel.tenantflow.infrastructure.security.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkOrderController.class)
@Import({SecurityConfiguration.class, JwtTenantContext.class, WorkOrderService.class})
class WorkOrderTransitionCharacterizationTest {

    private static final Instant NOW = Instant.parse("2026-06-27T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WorkOrderRepository repository;

    @MockitoBean
    Clock clock;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        reset(repository, clock);
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        when(repository.findByIdAndTenantId(OWNER_ID, "acme")).thenReturn(Optional.of(workOrder(
                WorkOrderStatus.OPEN, 0, NOW)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("forbiddenTransitions")
    void everyForbiddenTransitionIsNonMutating(
            WorkOrderStatus source, WorkOrderStatus target) throws Exception {
        var before = workOrder(source, 7, NOW);
        var snapshot = Snapshot.of(before);
        when(repository.findByIdAndTenantId(OWNER_ID, "acme")).thenReturn(Optional.of(before));

        mvc.perform(patch("/api/v1/work-orders/{id}/status", OWNER_ID)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_WORK_ORDER_WRITE"))
                                .jwt(builder -> builder
                                        .subject("writer")
                                        .claim("tenant_id", "acme")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + target + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Work order conflict"));

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        assertEquals(snapshot, Snapshot.of(before));
    }

    @Test
    void nonCancellationTransitionsAdvanceVersionsSequentially() {
        var repository = new VersioningRepository();
        var service = service(repository);

        var created = service.create(command("Sequential"));
        var inProgress = service.changeStatus(created.id(), WorkOrderStatus.IN_PROGRESS);
        var completed = service.changeStatus(created.id(), WorkOrderStatus.COMPLETED);

        assertEquals(WorkOrderStatus.OPEN, created.status());
        assertEquals(0, created.version());
        assertEquals(WorkOrderStatus.IN_PROGRESS, inProgress.status());
        assertEquals(1, inProgress.version());
        assertEquals(WorkOrderStatus.COMPLETED, completed.status());
        assertEquals(2, completed.version());
        assertEquals(created.id(), completed.id());
        assertEquals(created.title(), completed.title());
        assertEquals(created.createdAt(), completed.createdAt());
    }

    @ParameterizedTest(name = "{0} -> CANCELLED")
    @MethodSource("cancellationSources")
    void cancellationTransitionsIncrementVersionOnce(WorkOrderStatus source) {
        var repository = new VersioningRepository();
        var service = service(repository);
        var created = service.create(command("Cancellation"));
        if (source == WorkOrderStatus.IN_PROGRESS) {
            created = service.changeStatus(created.id(), WorkOrderStatus.IN_PROGRESS);
        }

        var cancelled = service.changeStatus(created.id(), WorkOrderStatus.CANCELLED);

        assertEquals(WorkOrderStatus.CANCELLED, cancelled.status());
        assertEquals(created.version() + 1, cancelled.version());
        assertEquals(created.id(), cancelled.id());
        assertEquals(created.title(), cancelled.title());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidStatusBodies")
    void invalidStatusSyntaxReturnsBadRequestWithoutSaving(
            String description, String body, boolean validationProblem) throws Exception {
        var result = mvc.perform(patch("/api/v1/work-orders/{id}/status", OWNER_ID)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_WORK_ORDER_WRITE"))
                                .jwt(builder -> builder
                                        .subject("writer")
                                        .claim("tenant_id", "acme")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        if (validationProblem) {
            result.andExpect(jsonPath("$.title").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.status").exists());
        }
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        assertEquals(Snapshot.of(workOrder(WorkOrderStatus.OPEN, 0, NOW)),
                Snapshot.of(repository.findByIdAndTenantId(OWNER_ID, "acme").orElseThrow()));
    }

    private WorkOrderService service(WorkOrderRepository repository) {
        return new WorkOrderService(
                repository,
                (TenantContext) () -> "acme",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CreateWorkOrderCommand command(String title) {
        return new CreateWorkOrderCommand(
                title, "description", WorkOrderPriority.HIGH,
                "owner@example.test", NOW.plusSeconds(3600));
    }

    private static Stream<Arguments> forbiddenTransitions() {
        return Stream.of(
                Arguments.of(WorkOrderStatus.OPEN, WorkOrderStatus.OPEN),
                Arguments.of(WorkOrderStatus.OPEN, WorkOrderStatus.COMPLETED),
                Arguments.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.OPEN),
                Arguments.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.IN_PROGRESS),
                Arguments.of(WorkOrderStatus.COMPLETED, WorkOrderStatus.OPEN),
                Arguments.of(WorkOrderStatus.COMPLETED, WorkOrderStatus.IN_PROGRESS),
                Arguments.of(WorkOrderStatus.COMPLETED, WorkOrderStatus.COMPLETED),
                Arguments.of(WorkOrderStatus.COMPLETED, WorkOrderStatus.CANCELLED),
                Arguments.of(WorkOrderStatus.CANCELLED, WorkOrderStatus.OPEN),
                Arguments.of(WorkOrderStatus.CANCELLED, WorkOrderStatus.IN_PROGRESS),
                Arguments.of(WorkOrderStatus.CANCELLED, WorkOrderStatus.COMPLETED),
                Arguments.of(WorkOrderStatus.CANCELLED, WorkOrderStatus.CANCELLED));
    }

    private static Stream<WorkOrderStatus> cancellationSources() {
        return Stream.of(WorkOrderStatus.OPEN, WorkOrderStatus.IN_PROGRESS);
    }

    private static Stream<Arguments> invalidStatusBodies() {
        return Stream.of(
                Arguments.of("missing status", "{}", true),
                Arguments.of("null status", "{\"status\":null}", true),
                Arguments.of("unknown enum", "{\"status\":\"UNKNOWN\"}", false),
                Arguments.of("malformed JSON", "{", false));
    }

    private static WorkOrder workOrder(WorkOrderStatus status, long version, Instant updatedAt) {
        return WorkOrder.rehydrate(
                OWNER_ID, "acme", "Transition record", "description",
                WorkOrderPriority.HIGH, status, "owner@example.test",
                NOW.plusSeconds(3600), NOW.minusSeconds(60), updatedAt, version);
    }

    private record Snapshot(
            UUID id,
            String tenantId,
            String title,
            String description,
            WorkOrderPriority priority,
            WorkOrderStatus status,
            String assigneeEmail,
            Instant dueAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        static Snapshot of(WorkOrder workOrder) {
            return new Snapshot(
                    workOrder.id(), workOrder.tenantId(), workOrder.title(),
                    workOrder.description(), workOrder.priority(), workOrder.status(),
                    workOrder.assigneeEmail(), workOrder.dueAt(), workOrder.createdAt(),
                    workOrder.updatedAt(), workOrder.version());
        }
    }

    private static final class VersioningRepository implements WorkOrderRepository {
        private final Map<UUID, WorkOrder> data = new LinkedHashMap<>();

        @Override
        public WorkOrder save(WorkOrder workOrder) {
            var existing = data.get(workOrder.id());
            long persistedVersion = existing == null ? 0 : existing.version() + 1;
            var persisted = WorkOrder.rehydrate(
                    workOrder.id(), workOrder.tenantId(), workOrder.title(),
                    workOrder.description(), workOrder.priority(), workOrder.status(),
                    workOrder.assigneeEmail(), workOrder.dueAt(), workOrder.createdAt(),
                    workOrder.updatedAt(), persistedVersion);
            data.put(persisted.id(), persisted);
            return persisted;
        }

        @Override
        public Optional<WorkOrder> findByIdAndTenantId(UUID id, String tenantId) {
            return Optional.ofNullable(data.get(id))
                    .filter(workOrder -> workOrder.tenantId().equals(tenantId));
        }

        @Override
        public List<WorkOrder> findAllByTenantId(String tenantId) {
            return data.values().stream()
                    .filter(workOrder -> workOrder.tenantId().equals(tenantId))
                    .toList();
        }
    }
}
