package dev.daniel.tenantflow.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SignedJwtDecoderCharacterizationTest.ProbeController.class)
@Import({SecurityConfiguration.class, SignedJwtDecoderCharacterizationTest.ProbeController.class})
class SignedJwtDecoderCharacterizationTest {

    private static final JwtTestIssuer ISSUER = JwtTestIssuer.start();
    private static final AtomicInteger CONTROLLER_INVOCATIONS = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwkSetUri);
    }

    @BeforeEach
    void resetControllerInvocations() {
        CONTROLLER_INVOCATIONS.set(0);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    @Test
    void rejectsMalformedBearerBeforeController() throws Exception {
        mvc.perform(get("/signed-probe").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        assertEquals(0, CONTROLLER_INVOCATIONS.get());
    }

    @Test
    void rejectsBadSignatureBeforeController() throws Exception {
        mvc.perform(get("/signed-probe")
                        .header("Authorization", "Bearer " + ISSUER.badSignatureToken()))
                .andExpect(status().isUnauthorized());

        assertEquals(0, CONTROLLER_INVOCATIONS.get());
    }

    @Test
    void rejectsExpiredTokenBeforeController() throws Exception {
        mvc.perform(get("/signed-probe")
                        .header("Authorization", "Bearer " + ISSUER.expiredToken()))
                .andExpect(status().isUnauthorized());

        assertEquals(0, CONTROLLER_INVOCATIONS.get());
    }

    @Test
    void rejectsWrongIssuerBeforeController() throws Exception {
        mvc.perform(get("/signed-probe")
                        .header("Authorization", "Bearer " + ISSUER.wrongIssuerToken()))
                .andExpect(status().isUnauthorized());

        assertEquals(0, CONTROLLER_INVOCATIONS.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("audienceForms")
    void acceptsAllTrustedIssuerAudienceForms(String description, List<String> audience) throws Exception {
        mvc.perform(get("/signed-probe")
                        .header("Authorization", "Bearer " + ISSUER.token(
                                List.of("WORK_ORDER_READ"), List.of(), "acme", audience)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        assertEquals(1, CONTROLLER_INVOCATIONS.get(), description);
    }

    private static Stream<Arguments> audienceForms() {
        return Stream.of(
                Arguments.of("aud omitted", null),
                Arguments.of("aud unrelated", List.of("another-api")),
                Arguments.of("aud names this API", List.of("tenantflow-api")));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/signed-probe")
        Map<String, Boolean> probe() {
            CONTROLLER_INVOCATIONS.incrementAndGet();
            return Map.of("accepted", true);
        }
    }
}

final class JwtTestIssuer implements AutoCloseable {

    private final RSAKey trustedKey;
    private final RSAKey untrustedKey;
    private final NimbusJwtEncoder trustedEncoder;
    private final NimbusJwtEncoder untrustedEncoder;
    private final HttpServer server;
    private final String issuer;

    private JwtTestIssuer() throws Exception {
        trustedKey = new RSAKeyGenerator(2048)
                .keyID("trusted-test-key")
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        untrustedKey = new RSAKeyGenerator(2048)
                .keyID(trustedKey.getKeyID())
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        trustedEncoder = encoder(trustedKey);
        untrustedEncoder = encoder(untrustedKey);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jwkSet = new JWKSet(trustedKey.toPublicJWK())
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwkSet.length);
            exchange.getResponseBody().write(jwkSet);
            exchange.close();
        });
        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
    }

    static JwtTestIssuer start() {
        try {
            return new JwtTestIssuer();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create ephemeral JWT issuer", exception);
        }
    }

    String issuer() {
        return issuer;
    }

    String jwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    String token(List<String> roles, List<String> scopes, String tenantId, List<String> audience) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("characterization-user")
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(300))
                .claim("realm_access", Map.of("roles", roles))
                .claim("scope", String.join(" ", scopes))
                .claim("tenant_id", tenantId);
        if (audience != null) {
            claims.audience(audience);
        }
        return encode(trustedEncoder, trustedKey, claims.build());
    }

    String badSignatureToken() {
        var now = Instant.now();
        var claims = validClaims(issuer, now.minusSeconds(1), now.plusSeconds(300));
        return encode(untrustedEncoder, untrustedKey, claims);
    }

    String expiredToken() {
        var now = Instant.now();
        var claims = validClaims(issuer, now.minusSeconds(600), now.minusSeconds(120));
        return encode(trustedEncoder, trustedKey, claims);
    }

    String wrongIssuerToken() {
        var now = Instant.now();
        var claims = validClaims("https://wrong-issuer.invalid", now.minusSeconds(1), now.plusSeconds(300));
        return encode(trustedEncoder, trustedKey, claims);
    }

    private JwtClaimsSet validClaims(String tokenIssuer, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer(tokenIssuer)
                .subject("characterization-user")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("realm_access", Map.of("roles", List.of("WORK_ORDER_READ")))
                .claim("tenant_id", "acme")
                .build();
    }

    private static NimbusJwtEncoder encoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }

    private static String encode(NimbusJwtEncoder encoder, RSAKey key, JwtClaimsSet claims) {
        var header = org.springframework.security.oauth2.jwt.JwsHeader
                .with(SignatureAlgorithm.RS256)
                .keyId(key.getKeyID())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
