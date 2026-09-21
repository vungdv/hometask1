package vn.danang.polaris.assistant.intent;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Encapsulates the resolved intent along with its confidence, threshold evaluation,
 * and the accepted tools for the current conversation turn.
 */
public record ResolvedIntent(
        String intentId,
        double confidence,
        boolean meetsThreshold,
        List<Tool> acceptedTools
) {
    public ResolvedIntent {
        acceptedTools = acceptedTools != null ? List.copyOf(acceptedTools) : List.of();
    }

    public List<Tool> tools() {
        return acceptedTools;
    }

    public List<Tool> filteredTools() {
        return acceptedTools;
    }
}
