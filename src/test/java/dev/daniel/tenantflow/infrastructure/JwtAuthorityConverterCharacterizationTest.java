package dev.daniel.tenantflow.infrastructure;

import dev.daniel.tenantflow.infrastructure.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@WebMvcTest(JwtAuthorityConverterCharacterizationTest.NoEndpoints.class)
@Import(SecurityConfiguration.class)
class JwtAuthorityConverterCharacterizationTest {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    Converter<Jwt, AbstractAuthenticationToken> converter;

    @Test
    void mapsRealmRolesAndScopesToExactAuthorities() {
        var authentication = converter.convert(jwt(Map.of(
                "scope", "profile email",
                "realm_access", Map.of("roles", List.of("WORK_ORDER_READ", "WORK_ORDER_WRITE")))));

        assertEquals(Set.of(
                        "SCOPE_profile",
                        "SCOPE_email",
                        "ROLE_WORK_ORDER_READ",
                        "ROLE_WORK_ORDER_WRITE"),
                authorities(authentication));
    }

    @Test
    void scopeNamesNeverBecomeRealmRoleAuthorities() {
        var authentication = converter.convert(jwt(Map.of(
                "scope", "WORK_ORDER_READ WORK_ORDER_WRITE")));

        assertEquals(Set.of("SCOPE_WORK_ORDER_READ", "SCOPE_WORK_ORDER_WRITE"),
                authorities(authentication));
    }

    @Test
    void missingOrMalformedRealmAccessAddsNoRoleAuthorities() {
        var missing = converter.convert(jwt(Map.of("scope", "profile")));
        var malformedRoles = converter.convert(jwt(Map.of(
                "scope", "profile",
                "realm_access", Map.of("roles", "WORK_ORDER_READ"))));

        assertEquals(Set.of("SCOPE_profile"), authorities(missing));
        assertEquals(Set.of("SCOPE_profile"), authorities(malformedRoles));
    }

    @Test
    void keepsSubjectAsTheDefaultPrincipalName() {
        var authentication = converter.convert(jwt(Map.of()));

        assertEquals("characterization-subject", authentication.getName());
    }

    private Jwt jwt(Map<String, Object> claims) {
        var now = Instant.now();
        var builder = Jwt.withTokenValue("converter-only")
                .header("alg", "none")
                .subject("characterization-subject")
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private Set<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
    }

    @RestController
    static class NoEndpoints {
    }
}
