package dev.daniel.tenantflow.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.daniel.tenantflow.api.WorkOrderController;
import dev.daniel.tenantflow.application.WorkOrderService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkOrderController.class)
@Import({
        SecurityConfiguration.class,
        JwtTenantContext.class,
        WorkOrderService.class,
        WorkOrderApiContractCharacterizationTest.RepositoryConfiguration.class
})
class WorkOrderApiContractCharacterizationTest {

    private static final Instant NOW = Instant.parse("2026-06-27T12:00:00Z");
    private static final UUID FOREIGN_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ABSENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000099");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RecordingRepository repository;

    @MockitoBean
    Clock clock;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        reset(clock);
        when(clock.instant()).thenReturn(NOW);
        repository.reset();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCreateRequests")
    void createValidationReturnsFieldErrorWithoutPersistence(
            String description, String body, String expectedField) throws Exception {
        int beforeCount = repository.size();

        mvc.perform(post("/api/v1/work-orders")
                        .with(writer("acme"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors." + expectedField).exists());

        assertEquals(beforeCount, repository.size(), description);
    }

    @Test
    void verifiedTenantClaimOverridesAnyClientSuppliedTenantIdentity() throws Exception {
        var result = mvc.perform(post("/api/v1/work-orders")
                        .with(writer("acme"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Claim-owned record",
                                  "description": "client attempted foreign ownership",
                                  "priority": "HIGH",
                                  "assigneeEmail": "owner@example.test",
                                  "dueAt": "2099-06-28T12:00:00Z",
                                  "tenantId": "globex"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.matchesPattern("/api/v1/work-orders/[0-9a-f-]+")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andReturn();

        Map<String, Object> body = body(result);
        var id = UUID.fromString(body.get("id").toString());
        var saved = repository.findByIdAndTenantId(id, "acme").orElseThrow();
        assertEquals("acme", saved.tenantId());
        assertTrue(repository.findByIdAndTenantId(id, "globex").isEmpty());

        mvc.perform(get("/api/v1/work-orders/{id}", id).with(reader("globex")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/work-orders").with(reader("globex")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());
    }

    @Test
    void foreignAndAbsentReadsAreNonDisclosingEquivalents() throws Exception {
        var foreign = mvc.perform(get("/api/v1/work-orders/{id}", FOREIGN_ID).with(reader("acme")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Work order not found"))
                .andReturn();
        var absent = mvc.perform(get("/api/v1/work-orders/{id}", ABSENT_ID).with(reader("acme")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Work order not found"))
                .andReturn();

        assertEquivalentProblems(foreign, absent, FOREIGN_ID, ABSENT_ID);
        assertFalse(foreign.getResponse().getContentAsString().contains("globex"));
        assertFalse(foreign.getResponse().getContentAsString().contains("Foreign record"));

        mvc.perform(get("/api/v1/work-orders").with(reader("acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + FOREIGN_ID + "')]").isEmpty());
    }

    @Test
    void foreignAndAbsentUpdatesAreNonDisclosingAndDoNotMutate() throws Exception {
        var before = Snapshot.of(repository.findByIdAndTenantId(FOREIGN_ID, "globex").orElseThrow());
        String patch = "{\"status\":\"IN_PROGRESS\"}";

        var foreign = mvc.perform(patch("/api/v1/work-orders/{id}/status", FOREIGN_ID)
                        .with(writer("acme"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Work order not found"))
                .andReturn();
        var absent = mvc.perform(patch("/api/v1/work-orders/{id}/status", ABSENT_ID)
                        .with(writer("acme"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Work order not found"))
                .andReturn();

        assertEquivalentProblems(foreign, absent, FOREIGN_ID, ABSENT_ID);
        assertEquals(before,
                Snapshot.of(repository.findByIdAndTenantId(FOREIGN_ID, "globex").orElseThrow()));
    }

    private void assertEquivalentProblems(
            MvcResult foreign, MvcResult absent, UUID foreignId, UUID absentId) throws Exception {
        assertEquals(foreign.getResponse().getStatus(), absent.getResponse().getStatus());
        assertEquals(foreign.getResponse().getContentType(), absent.getResponse().getContentType());
        assertEquals(Set.copyOf(foreign.getResponse().getHeaderNames()),
                Set.copyOf(absent.getResponse().getHeaderNames()));
        assertEquals(normalizedProblem(foreign, foreignId), normalizedProblem(absent, absentId));
    }

    private Map<String, Object> normalizedProblem(MvcResult result, UUID id) throws Exception {
        var normalized = body(result);
        normalized.replaceAll((key, value) ->
                value instanceof String text ? text.replace(id.toString(), "<id>") : value);
        assertEquals(Set.of("type", "title", "status", "detail", "instance"), normalized.keySet());
        assertNotNull(normalized.get("detail"));
        return normalized;
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
    writer(String tenantId) {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_WORK_ORDER_WRITE"))
                .jwt(builder -> builder.subject("writer").claim("tenant_id", tenantId));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
    reader(String tenantId) {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_WORK_ORDER_READ"))
                .jwt(builder -> builder.subject("reader").claim("tenant_id", tenantId));
    }

    private static Stream<Arguments> invalidCreateRequests() {
        String longTitle = "x".repeat(161);
        String longEmail = "x".repeat(246) + "@test.com";
        String longDescription = "x".repeat(4001);
        return Stream.of(
                Arguments.of("missing title",
                        "{\"priority\":\"HIGH\"}", "title"),
                Arguments.of("blank title",
                        "{\"title\":\"  \",\"priority\":\"HIGH\"}", "title"),
                Arguments.of("title over 160",
                        "{\"title\":\"" + longTitle + "\",\"priority\":\"HIGH\"}", "title"),
                Arguments.of("missing priority",
                        "{\"title\":\"Missing priority\"}", "priority"),
                Arguments.of("invalid email",
                        "{\"title\":\"Invalid email\",\"priority\":\"HIGH\",\"assigneeEmail\":\"not-an-email\"}",
                        "assigneeEmail"),
                Arguments.of("email over 254",
                        "{\"title\":\"Long email\",\"priority\":\"HIGH\",\"assigneeEmail\":\""
                                + longEmail + "\"}", "assigneeEmail"),
                Arguments.of("description over 4000",
                        "{\"title\":\"Long description\",\"priority\":\"HIGH\",\"description\":\""
                                + longDescription + "\"}", "description"),
                Arguments.of("non-future dueAt",
                        "{\"title\":\"Past due\",\"priority\":\"HIGH\",\"dueAt\":\"2000-01-01T00:00:00Z\"}",
                        "dueAt"));
    }

    @TestConfiguration
    static class RepositoryConfiguration {

        @Bean
        RecordingRepository workOrderRepository() {
            return new RecordingRepository();
        }
    }

    static class RecordingRepository implements WorkOrderRepository {
        private final Map<UUID, WorkOrder> data = new LinkedHashMap<>();

        void reset() {
            data.clear();
            data.put(FOREIGN_ID, WorkOrder.rehydrate(
                    FOREIGN_ID, "globex", "Foreign record", "foreign description",
                    WorkOrderPriority.CRITICAL, WorkOrderStatus.OPEN,
                    "foreign@example.test", NOW.plusSeconds(7200),
                    NOW.minusSeconds(120), NOW.minusSeconds(120), 0));
        }

        int size() {
            return data.size();
        }

        @Override
        public WorkOrder save(WorkOrder workOrder) {
            data.put(workOrder.id(), workOrder);
            return workOrder;
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
}
