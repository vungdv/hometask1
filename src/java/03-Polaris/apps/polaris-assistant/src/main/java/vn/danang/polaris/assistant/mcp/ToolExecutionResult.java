package vn.danang.polaris.assistant.mcp;

import java.util.List;

import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Result of handling a batch of tool calls by {@link McpHub}.
 *
 * @param messages the conversation turn messages generated (model turns followed by tool turns)
 * @param policyDenied whether any tool call was denied by policy authorization
 * @param denialReason the explanation for policy denial, if denied
 */
public record ToolExecutionResult(
        List<AssistantMessage> messages,
        boolean policyDenied,
        @Nullable String denialReason
) {

    public static ToolExecutionResult empty() {
        return new ToolExecutionResult(List.of(), false, null);
    }

    public static ToolExecutionResult denied(List<AssistantMessage> messages, String reason) {
        return new ToolExecutionResult(messages != null ? messages : List.of(), true, reason);
    }

    public static ToolExecutionResult success(List<AssistantMessage> messages) {
        return new ToolExecutionResult(messages != null ? messages : List.of(), false, null);
    }
}
