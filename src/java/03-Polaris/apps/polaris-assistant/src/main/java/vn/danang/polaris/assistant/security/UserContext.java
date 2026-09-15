package vn.danang.polaris.assistant.security;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.mcp.PolarisMcpProperties;

/**
 * Accessor for user authentication state and security tokens in the current execution context.
 * Decouples Spring Security context inspection from downstream clients and transports.
 */
@Component
public class UserContext {

    private static final Logger log = LoggerFactory.getLogger(UserContext.class);

    private final PolarisMcpProperties properties;

    @Autowired
    public UserContext(PolarisMcpProperties properties) {
        this.properties = properties;
    }

    public UserContext() {
        this(null);
    }

    /**
     * Resolves the current Bearer token from Spring Security's {@link SecurityContextHolder}.
     * Checks for an authenticated user's JWT/OAuth2 token, then falls back to any configured
     * static service auth token in application properties.
     *
     * @return the resolved Bearer token string, or null if unauthenticated and no fallback exists
     */
    public String resolveBearerToken() {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context != null && context.getAuthentication() != null) {
            Authentication auth = context.getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                return jwtAuth.getToken().getTokenValue();
            } else if (auth.getPrincipal() instanceof Jwt jwt) {
                return jwt.getTokenValue();
            } else if (auth.getCredentials() instanceof AbstractOAuth2Token oauth2Token) {
                return oauth2Token.getTokenValue();
            } else if (auth.getCredentials() instanceof String cred && !cred.isBlank()) {
                return cred;
            }
        }

        if (properties != null && properties.getCore() != null) {
            String fallbackToken = properties.getCore().getAuthToken();
            if (fallbackToken != null && !fallbackToken.isBlank()) {
                return fallbackToken.trim();
            }
        }

        return null;
    }

    /**
     * Retrieves the identifier of the currently authenticated user, or empty if unauthenticated.
     *
     * @return optional containing the user identifier (JWT subject or principal name)
     */
    public Optional<String> getCurrentUserId() {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context != null && context.getAuthentication() != null) {
            Authentication auth = context.getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                return Optional.ofNullable(jwtAuth.getToken().getSubject());
            } else if (auth.getPrincipal() instanceof Jwt jwt) {
                return Optional.ofNullable(jwt.getSubject());
            } else if (auth.getName() != null && !auth.getName().isBlank()) {
                return Optional.of(auth.getName());
            }
        }
        return Optional.empty();
    }
}
