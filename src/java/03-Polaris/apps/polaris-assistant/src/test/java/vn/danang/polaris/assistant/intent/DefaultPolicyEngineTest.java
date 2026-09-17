package vn.danang.polaris.assistant.intent;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    @Test
    @DisplayName("Should allow when requiredScope is null or blank")
    void authorize_withNullOrBlankScope_allows() {
        PolicyDecision decisionNull = policyEngine.authorize("user-1", null);
        assertThat(decisionNull.allowed()).isTrue();

        PolicyDecision decisionBlank = policyEngine.authorize("user-1", "   ");
        assertThat(decisionBlank.allowed()).isTrue();
    }

    @Test
    @DisplayName("Should allow when user has matching scope in JwtAuthenticationToken")
    void authorize_withMatchingScopeInJwt_allows() {
        Jwt jwt = Jwt.withTokenValue("ey-jwt-test")
                .header("alg", "none")
                .claim("sub", "customer-100")
                .claim("scope", "catalog.read order.read")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        PolicyDecision readDecision = policyEngine.authorize("customer-100", "catalog.read");
        assertThat(readDecision.allowed()).isTrue();

        PolicyDecision orderReadDecision = policyEngine.authorize("customer-100", "order.read");
        assertThat(orderReadDecision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Should deny when user token lacks the required scope")
    void authorize_whenScopeMissingInJwt_denies() {
        Jwt jwt = Jwt.withTokenValue("ey-jwt-test")
                .header("alg", "none")
                .claim("sub", "customer-100")
                .claim("scope", "catalog.read")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        PolicyDecision writeDecision = policyEngine.authorize("customer-100", "order.write");
        assertThat(writeDecision.allowed()).isFalse();
        assertThat(writeDecision.reason()).contains("lacks required scope 'order.write'");
    }

    @Test
    @DisplayName("Should deny when caller is unauthenticated anonymous user and scope is required")
    void authorize_whenAnonymousWithoutAuth_denies() {
        PolicyDecision decision = policyEngine.authorize("anonymous", "order.write");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("Authentication required");
    }

    @Test
    @DisplayName("Should allow non-anonymous caller in non-secured environment when scope is required")
    void authorize_whenNonSecuredContext_permitsNonAnonymousUser() {
        PolicyDecision decision = policyEngine.authorize("user-test-runner", "order.read");
        assertThat(decision.allowed()).isTrue();
    }
}
