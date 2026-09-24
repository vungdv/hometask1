package vn.danang.polaris.assistant.policy;

public interface PolicyEngine {

    /**
     * Authorizes an action requiring a specific permission for the current caller.
     * The permission is checked against the caller's {@code PERM_}-prefixed authorities
     * (e.g. {@code order.read} is granted by authority {@code PERM_order.read}).
     *
     * @param requiredScope the required permission, or null if no permission is required
     * @return PolicyDecision indicating whether execution is permitted
     */
    PolicyDecision authorize(String requiredScope);
}
