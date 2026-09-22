package vn.danang.polaris.assistant.mcp;

import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * Result of policy and intent checks performed on a tool call.
 * Encapsulates the approved {@link ToolCall} or the rejection {@link ToolResult}.
 */
public record ToolPolicyCheckResult(
        ToolCall toolCall,
        @Nullable ToolResult rejection
) {
    public static ToolPolicyCheckResult ok(ToolCall toolCall) {
        return new ToolPolicyCheckResult(toolCall, null);
    }

    public static ToolPolicyCheckResult reject(ToolCall toolCall, ToolResult rejection) {
        return new ToolPolicyCheckResult(toolCall, rejection);
    }

    public boolean isOk() {
        return rejection == null;
    }

    public boolean isRejected() {
        return rejection != null;
    }

    /**
     * Functional continuation: if policy check is ok, passes the valid tool call to the execution function;
     * otherwise completes immediately with the policy rejection result.
     */
//    public CompletableFuture<ToolResult> ifOkPassToExecute(
//            Function<ToolCall, CompletableFuture<ToolResult>> executor) {
//        if (isOk()) {
//            return executor.apply(toolCall);
//        }
//        return CompletableFuture.completedFuture(rejection);
//    }
}