package vn.danang.polaris.assistant.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import vn.danang.polaris.assistant.mcp.PolarisMcpProperties;

class UserContextTest {

    private PolarisMcpProperties properties;
    private UserContext userContext;

    @BeforeEach
    void setUp() {
        properties = new PolarisMcpProperties();
        userContext = new UserContext(properties);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testResolveBearerToken_withJwtAuthenticationToken_extractsTokenValue() {
        Jwt jwt = Jwt.withTokenValue("ey-user-token-abc")
                .header("alg", "none")
                .claim("sub", "user-123")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        String token = userContext.resolveBearerToken();
        assertEquals("ey-user-token-abc", token);
    }

    @Test
    void testResolveBearerToken_withFallbackProperty_whenNoSecurityContext() {
        properties.getCore().setAuthToken("fallback-service-token");
        userContext = new UserContext(properties);

        String token = userContext.resolveBearerToken();
        assertEquals("fallback-service-token", token);
    }

    @Test
    void testResolveBearerToken_withoutSecurityContextOrFallback_returnsNull() {
        properties.getCore().setAuthToken(null);
        userContext = new UserContext(properties);

        String token = userContext.resolveBearerToken();
        assertNull(token);
    }

    @Test
    void testGetCurrentUserId_withJwtAuthenticationToken() {
        Jwt jwt = Jwt.withTokenValue("ey-token")
                .header("alg", "none")
                .claim("sub", "customer-999")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        Optional<String> userId = userContext.getCurrentUserId();
        assertTrue(userId.isPresent());
        assertEquals("customer-999", userId.get());
    }

    @Test
    void testGetCurrentUserId_whenUnauthenticated_returnsEmpty() {
        Optional<String> userId = userContext.getCurrentUserId();
        assertTrue(userId.isEmpty());
    }
}
