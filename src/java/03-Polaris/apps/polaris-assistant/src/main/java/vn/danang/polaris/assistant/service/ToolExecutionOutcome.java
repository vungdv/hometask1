package vn.danang.polaris.assistant.service;

import java.util.List;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * Encapsulates the outcome of executing a batch of tool calls during a conversation turn.
 *
 * @param turns the generated conversation turns (model turns followed by tool turns)
 * @param policyDenied whether any tool call was denied by policy authorization
 * @param denialMessage user-facing denial explanation if policy denied, or null
 * @param toolResults the raw per-call results, needed by {@code streamTurn} (WO-020) to tell a
 *                     {@code stage_order_draft} {@code Staged}/{@code Rejected} outcome apart so it
 *                     can emit the right SSE {@code draft}/{@code problem} event; {@code sendMessage}
 *                     ignores this field and only reads the flattened {@code turns}
 */
public record ToolExecutionOutcome(
        List<AssistantMessage> turns,
        boolean policyDenied,
        @Nullable String denialMessage,
        List<ToolResult> toolResults
) {
    public ToolExecutionOutcome {
        turns = turns != null ? List.copyOf(turns) : List.of();
        toolResults = toolResults != null ? List.copyOf(toolResults) : List.of();
    }
}
