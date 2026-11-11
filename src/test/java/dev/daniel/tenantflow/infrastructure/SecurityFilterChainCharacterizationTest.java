package dev.daniel.tenantflow.infrastructure;

import dev.daniel.tenantflow.api.WorkOrderController;
import dev.daniel.tenantflow.application.WorkOrderService;
import dev.daniel.tenantflow.application.port.WorkOrderRepository;
import dev.daniel.tenantflow.domain.WorkOrder;
import dev.daniel.tenantflow.domain.WorkOrderPriority;
import dev.daniel.tenantflow.domain.WorkOrderStatus;
import dev.daniel.tenantflow.infrastructure.security.JwtTenantContext;
import dev.daniel.tenantflow.infrastructure.security.SecurityConfiguration;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkOrderController.class)
@Import({
        SecurityConfiguration.class,
        JwtTenantContext.class,
        WorkOrderService.class,
        SecurityFilterChainCharacterizationTest.PublicProbeController.class
})
class SecurityFilterChainCharacterizationTest {

    private static final JwtTestIssuer ISSUER = JwtTestIssuer.start();
    private static final Instant NOW = Instant.parse("2026-06-27T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WorkOrderRepository repository;

    @MockitoBean
    Clock clock;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
    }

    @BeforeEach
    void setUpRepository() {
        reset(repository, clock);
        when(clock.instant()).thenReturn(NOW);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findAllByTenantId("acme")).thenReturn(List.of(owner()));
        when(repository.findByIdAndTenantId(OWNER_ID, "acme")).thenReturn(Optional.of(owner()));
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @Test
    void anonymousProtectedOperationsReturnUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/work-orders"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/work-orders/{id}", OWNER_ID))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/work-orders/{id}/status", OWNER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(repository);
    }

    @Test
    void configuredPublicPathsRemainPermitAll() throws Exception {
        mvc.perform(get("/actuator/health/characterization"))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/characterization"))
                .andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/characterization"))
                .andExpect(status().isOk());
    }

    @Test
    void readRealmRoleAllowsOnlyListAndItemReads() throws Exception {
        var token = bearer(List.of("WORK_ORDER_READ"), List.of());

        mvc.perform(get("/api/v1/work-orders").header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/work-orders/{id}", OWNER_ID).header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/work-orders/{id}/status", OWNER_ID)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeRealmRoleAllowsOnlyCreateAndStatusUpdate() throws Exception {
        var token = bearer(List.of("WORK_ORDER_WRITE"), List.of());

        mvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/v1/work-orders/{id}/status", OWNER_ID)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/work-orders").header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-orders/{id}", OWNER_ID).header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonRoleAuthorityCases")
    void noRealmWorkOrderRoleForbidsEveryWorkOrderOperation(
            String description, List<String> roles, List<String> scopes) throws Exception {
        var token = bearer(roles, scopes);

        mvc.perform(post("/api/v1/work-orders")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-orders").header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/work-orders/{id}", OWNER_ID).header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/work-orders/{id}/status", OWNER_ID)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "{0} tenant_id blocks {1}")
    @MethodSource("invalidTenantOperations")
    void everyInvalidTenantClaimBlocksEveryProtectedOperation(
            TenantClaimForm tenantClaim, String operation, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(invalidTenantJwt(tenantClaim)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid tenant context"))
                .andExpect(jsonPath("$.detail")
                        .value("Authenticated token does not contain a tenant_id claim"));

        verifyNoInteractions(repository);
    }

    @Test
    void authenticatedNoRoleTokenCanReadPrometheus() throws Exception {
        mvc.perform(get("/actuator/prometheus")
                        .header("Authorization", bearer(List.of(), List.of())))
                .andExpect(status().isOk());
    }

    private String bearer(List<String> roles, List<String> scopes) {
        return "Bearer " + ISSUER.token(roles, scopes, "acme", List.of("tenantflow-api"));
    }

    private JwtRequestPostProcessor invalidTenantJwt(TenantClaimForm tenantClaim) {
        return jwt()
                .authorities(
                        new SimpleGrantedAuthority("ROLE_WORK_ORDER_READ"),
                        new SimpleGrantedAuthority("ROLE_WORK_ORDER_WRITE"))
                .jwt(builder -> {
                    builder.subject("characterization-user");
                    if (tenantClaim != TenantClaimForm.ABSENT) {
                        builder.claim("tenant_id", tenantClaim.value);
                    }
                });
    }

    private static Stream<Arguments> nonRoleAuthorityCases() {
        return Stream.of(
                Arguments.of("no work-order authority", List.of(), List.of()),
                Arguments.of("scope-only work-order names", List.of(),
                        List.of("WORK_ORDER_READ", "WORK_ORDER_WRITE")));
    }

    private static Stream<Arguments> invalidTenantOperations() {
        return Stream.of(TenantClaimForm.values())
                .flatMap(form -> Stream.of(
                        Arguments.of(form, "POST", post("/api/v1/work-orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validCreateJson())),
                        Arguments.of(form, "LIST", get("/api/v1/work-orders")),
                        Arguments.of(form, "ITEM_GET", get("/api/v1/work-orders/{id}", OWNER_ID)),
                        Arguments.of(form, "PATCH", patch("/api/v1/work-orders/{id}/status", OWNER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"IN_PROGRESS\"}"))));
    }

    private WorkOrder owner() {
        return WorkOrder.rehydrate(
                OWNER_ID, "acme", "Owner record", "description",
                WorkOrderPriority.HIGH, WorkOrderStatus.OPEN,
                "owner@example.test", NOW.plusSeconds(3600),
                NOW.minusSeconds(60), NOW.minusSeconds(60), 0);
    }

    private static String validCreateJson() {
        return """
                {
                  "title": "Investigate alert",
                  "description": "characterization",
                  "priority": "HIGH",
                  "assigneeEmail": "owner@example.test",
                  "dueAt": "2099-06-28T12:00:00Z"
                }
                """;
    }

    private enum TenantClaimForm {
        ABSENT(null),
        NULL(null),
        EMPTY(""),
        WHITESPACE(" \t ");

        private final String value;

        TenantClaimForm(String value) {
            this.value = value;
        }
    }

    @RestController
    static class PublicProbeController {

        @GetMapping({
                "/actuator/health/characterization",
                "/v3/api-docs/characterization",
                "/swagger-ui/characterization",
                "/actuator/prometheus"
        })
        String probe() {
            return "ok";
        }
    }
}
