package vn.danang.polaris.assistant.mcp;

import vn.danang.polaris.assistant.intent.ResolvedIntent;

/**
 * Immutable context containing turn and resolved intent metadata required to execute tool calls.
 *
 * @param sessionId the conversation session identifier
 * @param userId the caller user identity
 * @param iteration the 1-based ReAct loop iteration index
 * @param resolvedIntent the resolved intent metadata and accepted tools
 */
public record ToolExecutionContext(
        String sessionId,
        String userId,
        int iteration,
        ResolvedIntent resolvedIntent
) {
}

