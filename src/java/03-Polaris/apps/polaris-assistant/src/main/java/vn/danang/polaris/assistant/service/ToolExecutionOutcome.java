package vn.danang.polaris.assistant.service;

import java.util.List;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Encapsulates the outcome of executing a batch of tool calls during a conversation turn.
 *
 * @param turns the generated conversation turns (model turns followed by tool turns)
 * @param policyDenied whether any tool call was denied by policy authorization
 * @param denialMessage user-facing denial explanation if policy denied, or null
 */
public record ToolExecutionOutcome(
        List<AssistantMessage> turns,
        boolean policyDenied,
        @Nullable String denialMessage
) {
    public ToolExecutionOutcome {
        turns = turns != null ? List.copyOf(turns) : List.of();
    }
}
