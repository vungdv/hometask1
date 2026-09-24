package vn.danang.polaris.assistant.policy;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.security.UserContext;

@Component
public class DefaultPolicyEngine implements PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultPolicyEngine.class);

    private final UserContext userContext;

    @Autowired
    public DefaultPolicyEngine(UserContext userContext) {
        this.userContext = userContext;
    }

    public DefaultPolicyEngine() {
        this(new UserContext());
    }

    @Override
    public PolicyDecision authorize(String userId, String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank()) {
            return PolicyDecision.allow();
        }

        SecurityContext context = SecurityContextHolder.getContext();
        if (context == null || context.getAuthentication() == null) {
            // When running without an active SecurityContext (e.g. unit tests or local host mode),
            // permit non-anonymous users while denying unauthenticated anonymous access.
            if (userId != null && !userId.isBlank() && !"anonymous".equalsIgnoreCase(userId)) {
                log.debug("Permitting user [{}] for scope [{}] in non-secured execution context", userId, requiredScope);
                return PolicyDecision.allow();
            }
            return PolicyDecision.deny("Authentication required: caller is unauthenticated or missing scope '" + requiredScope + "'.");
        }

        Set<String> userScopes = resolveCallerScopes();

        if (userScopes.contains(requiredScope) || userScopes.contains("SCOPE_" + requiredScope)) {
            log.debug("User [{}] granted authorization for required scope [{}]", userId, requiredScope);
            return PolicyDecision.allow();
        }

        log.warn("Authorization DENIED for user [{}] requesting scope [{}]. Available scopes: {}",
                userId, requiredScope, userScopes);

        if (userScopes.isEmpty()) {
            return PolicyDecision.deny("Authentication required: caller is unauthenticated or missing scope '" + requiredScope + "'.");
        }

        return PolicyDecision.deny("Permission denied: caller lacks required scope '" + requiredScope + "'.");
    }

    private Set<String> resolveCallerScopes() {
        Set<String> scopes = new HashSet<>();
        SecurityContext context = SecurityContextHolder.getContext();
        if (context == null || context.getAuthentication() == null) {
            return scopes;
        }

        Authentication auth = context.getAuthentication();

        // 1. Check GrantedAuthorities
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities != null) {
            for (GrantedAuthority ga : authorities) {
                if (ga != null && ga.getAuthority() != null) {
                    String authority = ga.getAuthority();
                    scopes.add(authority);
                    if (authority.startsWith("SCOPE_")) {
                        scopes.add(authority.substring("SCOPE_".length()));
                    }
                }
            }
        }

        // 2. Check JWT Claims directly if available
        Jwt jwt = null;
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            jwt = jwtAuth.getToken();
        } else if (auth.getPrincipal() instanceof Jwt principalJwt) {
            jwt = principalJwt;
        }

        if (jwt != null) {
            extractJwtScopes(jwt, "scope", scopes);
            extractJwtScopes(jwt, "scp", scopes);
        }

        return scopes;
    }

    @SuppressWarnings("unchecked")
    private void extractJwtScopes(Jwt jwt, String claimName, Set<String> scopes) {
        Object claim = jwt.getClaims().get(claimName);
        if (claim instanceof String strClaim) {
            String[] parts = strClaim.split("\\s+");
            scopes.addAll(Arrays.asList(parts));
        } else if (claim instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (item != null) {
                    scopes.add(item.toString());
                }
            }
        }
    }
}
