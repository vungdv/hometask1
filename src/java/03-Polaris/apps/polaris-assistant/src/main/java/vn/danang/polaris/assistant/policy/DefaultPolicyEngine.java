package vn.danang.polaris.assistant.policy;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    public PolicyDecision authorize(String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank()) {
            return PolicyDecision.allow();
        }

        String requiredPermission = "PERM_" + requiredScope;

        if (!userContext.isAuthenticated()) {
            return PolicyDecision.deny("Authentication required: caller is unauthenticated or missing permission '" + requiredPermission + "'.");
        }

        if (userContext.hasPermission(requiredScope)) {
            log.debug("Caller granted authorization for required permission [{}]", requiredPermission);
            return PolicyDecision.allow();
        }

        Set<String> userPermissions = userContext.getPermissions();
        log.warn("Authorization DENIED for required permission [{}]. Available permissions: {}",
                requiredPermission, userPermissions);

        return PolicyDecision.deny("Permission denied: caller lacks required permission '" + requiredPermission + "'.");
    }
}
