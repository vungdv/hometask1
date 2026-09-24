package vn.danang.polaris.assistant.policy;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import vn.danang.polaris.assistant.security.UserContext;

class DefaultPolicyEngineTest {

    private DefaultPolicyEngine policyEngine;

    @BeforeEach
    void setUp() {
        policyEngine = new DefaultPolicyEngine(new UserContext());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // 1. Happy path — authorized access flows
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @ParameterizedTest(name = "Scope \"{0}\" does not require authorization")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Given null, empty, or blank requiredScope, when evaluated, then automatically allows access")
        void allows_when_required_scope_is_null_or_blank(String unconstrainedScope) {
            PolicyDecision decision = policyEngine.authorize(unconstrainedScope);

            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("Given GrantedAuthority with PERM_ prefix matching required scope, when evaluated, then authorizes caller")
        void allows_when_matching_permission_present_as_granted_authority() {
            setAuthenticationWithPermissions("order.write");

            PolicyDecision decision = policyEngine.authorize("order.write");

            assertThat(decision.allowed()).isTrue();
        }
    }

    // =========================================================================
    // 2. Invalid input & denials
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & denials")
    class InvalidInput {

        @Test
        @DisplayName("Given authenticated caller missing required permission, when evaluated, then denies access with permission message")
        void denies_when_caller_lacks_required_permission() {
            setAuthenticationWithPermissions("catalog.read");

            PolicyDecision decision = policyEngine.authorize("order.write");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("lacks required permission 'PERM_order.write'");
        }

        @Test
        @DisplayName("Given unauthenticated caller and required scope, when evaluated, then denies with authentication required message")
        void denies_when_caller_is_unauthenticated() {
            PolicyDecision decision = policyEngine.authorize("order.write");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("Authentication required");
        }
    }

    // =========================================================================
    // 3. Edge cases — default constructor
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given default constructor, when instantiated, then authorizes correctly")
        void operates_correctly_with_default_constructor() {
            DefaultPolicyEngine defaultEngine = new DefaultPolicyEngine();
            PolicyDecision decision = defaultEngine.authorize(null);

            assertThat(decision.allowed()).isTrue();
        }
    }

    private void setAuthenticationWithPermissions(String... permissions) {
        Jwt jwt = Jwt.withTokenValue("ey-jwt-test")
                .header("alg", "none")
                .claim("sub", "customer-100")
                .build();
        List<SimpleGrantedAuthority> authorities = Stream.of(permissions)
                .map(permission -> new SimpleGrantedAuthority("PERM_" + permission))
                .toList();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}
