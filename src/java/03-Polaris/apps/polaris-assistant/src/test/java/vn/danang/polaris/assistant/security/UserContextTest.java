package vn.danang.polaris.assistant.security;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import vn.danang.polaris.assistant.mcp.PolarisMcpProperties;

@DisplayName("Feature: UserContext Authentication & Security Token Resolution")
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

    // =========================================================================
    // 1. Happy path — authenticated caller resolutions
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given JwtAuthenticationToken in SecurityContext, when resolving token, then returns raw JWT token value")
        void resolves_bearer_token_from_jwt_authentication_token() {
            Jwt jwt = Jwt.withTokenValue("ey-user-token-abc")
                    .header("alg", "none")
                    .claim("sub", "user-123")
                    .build();
            JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            String token = userContext.resolveBearerToken();

            assertThat(token).isEqualTo("ey-user-token-abc");
        }

        @Test
        @DisplayName("Given JwtAuthenticationToken in SecurityContext, when resolving userId, then returns subject claim")
        void resolves_current_user_id_from_jwt_subject() {
            Jwt jwt = Jwt.withTokenValue("ey-token")
                    .header("alg", "none")
                    .claim("sub", "customer-999")
                    .build();
            JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            Optional<String> userId = userContext.getCurrentUserId();

            assertThat(userId).contains("customer-999");
        }

        @Test
        @DisplayName("Given standard Authentication in SecurityContext, when resolving userId, then returns principal name")
        void resolves_current_user_id_from_principal_name() {
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken("user-bob", "credentials");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            Optional<String> userId = userContext.getCurrentUserId();

            assertThat(userId).contains("user-bob");
        }
    }

    // =========================================================================
    // 2. Invalid input & unauthenticated states
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & unauthenticated states")
    class InvalidInput {

        @Test
        @DisplayName("Given no active authentication and no fallback property, when resolving token, then returns null")
        void returns_null_when_unauthenticated_and_no_fallback_configured() {
            properties.getCore().setAuthToken(null);
            userContext = new UserContext(properties);

            String token = userContext.resolveBearerToken();

            assertThat(token).isNull();
        }

        @Test
        @DisplayName("Given unauthenticated execution context, when resolving userId, then returns empty Optional")
        void returns_empty_user_id_when_unauthenticated() {
            Optional<String> userId = userContext.getCurrentUserId();

            assertThat(userId).isEmpty();
        }
    }

    // =========================================================================
    // 3. Edge cases — string credentials, fallback trimming, null properties
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given string credentials in Authentication, when resolving token, then returns credential string")
        void resolves_token_from_string_credentials() {
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken("user-alice", "raw-secret-token-123");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            String token = userContext.resolveBearerToken();

            assertThat(token).isEqualTo("raw-secret-token-123");
        }

        @Test
        @DisplayName("Given fallback token in properties with whitespace, when resolving, then trims and returns token")
        void resolves_fallback_auth_token_with_whitespace_trimming() {
            properties.getCore().setAuthToken("   service-static-key-456   ");
            userContext = new UserContext(properties);

            String token = userContext.resolveBearerToken();

            assertThat(token).isEqualTo("service-static-key-456");
        }

        @Test
        @DisplayName("Given null properties, when instantiated with default constructor, then operates safely without NPE")
        void operates_safely_with_default_constructor_and_null_properties() {
            UserContext defaultContext = new UserContext();

            assertThat(defaultContext.resolveBearerToken()).isNull();
            assertThat(defaultContext.getCurrentUserId()).isEmpty();
        }
    }
}
