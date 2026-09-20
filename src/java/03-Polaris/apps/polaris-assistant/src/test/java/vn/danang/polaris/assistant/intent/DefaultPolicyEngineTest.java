package vn.danang.polaris.assistant.intent;

import java.util.List;

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
            PolicyDecision decision = policyEngine.authorize("user-1", unconstrainedScope);

            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("Given JWT with matching scope in 'scope' claim, when evaluated, then authorizes caller")
        void allows_when_matching_scope_present_in_jwt_scope_claim() {
            setJwtAuthentication("customer-100", "scope", "catalog.read order.read");

            PolicyDecision decision = policyEngine.authorize("customer-100", "catalog.read");

            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("Given JWT with matching scope in 'scp' claim, when evaluated, then authorizes caller")
        void allows_when_matching_scope_present_in_jwt_scp_claim() {
            setJwtAuthentication("customer-100", "scp", "order.write");

            PolicyDecision decision = policyEngine.authorize("customer-100", "order.write");

            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("Given GrantedAuthority with SCOPE_ prefix, when evaluated, then authorizes caller")
        void allows_when_matching_scope_present_as_granted_authority() {
            Jwt jwt = Jwt.withTokenValue("ey-jwt-test")
                    .header("alg", "none")
                    .claim("sub", "customer-100")
                    .build();
            JwtAuthenticationToken auth = new JwtAuthenticationToken(
                    jwt,
                    List.of(new SimpleGrantedAuthority("SCOPE_order.write"))
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            PolicyDecision decision = policyEngine.authorize("customer-100", "order.write");

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
        @DisplayName("Given authenticated caller missing required scope, when evaluated, then denies access with permission message")
        void denies_when_caller_token_lacks_required_scope() {
            setJwtAuthentication("customer-100", "scope", "catalog.read");

            PolicyDecision decision = policyEngine.authorize("customer-100", "order.write");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("lacks required scope 'order.write'");
        }

        @Test
        @DisplayName("Given unauthenticated anonymous caller and required scope, when evaluated, then denies with authentication required message")
        void denies_when_anonymous_caller_lacks_authentication() {
            PolicyDecision decision = policyEngine.authorize("anonymous", "order.write");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("Authentication required");
        }
    }

    // =========================================================================
    // 3. Edge cases — non-secured contexts, claim formats, default constructor
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given non-secured context without SecurityContext, when caller is non-anonymous, then permits access")
        void permits_non_anonymous_user_in_non_secured_context() {
            PolicyDecision decision = policyEngine.authorize("developer-local-runner", "order.read");

            assertThat(decision.allowed()).isTrue();
        }

        @ParameterizedTest(name = "Anonymous identifier \"{0}\" denied in non-secured context")
        @NullAndEmptySource
        @ValueSource(strings = {"anonymous", "ANONYMOUS", "   "})
        @DisplayName("Given non-secured context, when caller identifier is blank or anonymous, then denies access")
        void denies_anonymous_identifiers_in_non_secured_context(String anonymousUser) {
            PolicyDecision decision = policyEngine.authorize(anonymousUser, "order.read");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("Authentication required");
        }

        @Test
        @DisplayName("Given JWT with collection-based 'scope' claim, when evaluated, then correctly resolves scopes")
        void allows_when_scope_claim_is_collection() {
            Jwt jwt = Jwt.withTokenValue("ey-jwt-test")
                    .header("alg", "none")
                    .claim("sub", "customer-100")
                    .claim("scope", List.of("catalog.read", "order.read"))
                    .build();
            JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            PolicyDecision decision = policyEngine.authorize("customer-100", "catalog.read");

            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("Given default constructor, when instantiated, then authorizes correctly")
        void operates_correctly_with_default_constructor() {
            DefaultPolicyEngine defaultEngine = new DefaultPolicyEngine();
            PolicyDecision decision = defaultEngine.authorize("user-test", "catalog.read");

            assertThat(decision.allowed()).isTrue();
        }
    }

    private void setJwtAuthentication(String subject, String claimName, Object claimValue) {
        Jwt jwt = Jwt.withTokenValue("ey-jwt-test")
                .header("alg", "none")
                .claim("sub", subject)
                .claim(claimName, claimValue)
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}
