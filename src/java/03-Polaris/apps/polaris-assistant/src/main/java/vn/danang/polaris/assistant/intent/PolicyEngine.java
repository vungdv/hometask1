package vn.danang.polaris.assistant.intent;

public interface PolicyEngine {

    /**
     * Authorizes an action requiring a specific OAuth2 scope for the given user.
     *
     * @param userId caller identifier
     * @param requiredScope the required OAuth2 scope, or null if no scope is required
     * @return PolicyDecision indicating whether execution is permitted
     */
    PolicyDecision authorize(String userId, String requiredScope);
}
