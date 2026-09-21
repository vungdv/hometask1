package vn.danang.polaris.assistant.mcp;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Immutable context containing turn and intent metadata required to execute tool calls.
 *
 * @param sessionId the conversation session identifier
 * @param userId the caller user identity
 * @param iteration the 1-based ReAct loop iteration index
 * @param intentId the resolved intent identifier
 * @param confidence the confidence score of the resolved intent
 * @param meetsThreshold whether intent confidence meets the configured threshold
 * @param filteredTools the allowed tools for this turn
 */
public record ToolExecutionContext(
        String sessionId,
        String userId,
        int iteration,
        String intentId,
        double confidence,
        boolean meetsThreshold,
        List<Tool> filteredTools
) {
}
