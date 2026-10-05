package vn.danang.polaris.assistant.intent;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;

/**
 * Encapsulates the resolved intent along with its confidence, threshold evaluation,
 * accepted tools, and the underlying intent definition for the current conversation turn.
 * {@code degradedNotice} is set when the intent classifier was unavailable and the turn was pinned to a fixed
 * intent; the reply then leads with it.
 */
public record ResolvedIntent(
        String intentId,
        double confidence,
        boolean meetsThreshold,
        List<Tool> acceptedTools,
        IntentDefinition intentDefinition,
        @Nullable String degradedNotice
) {

    public ResolvedIntent(
            String intentId,
            double confidence,
            boolean meetsThreshold,
            List<Tool> acceptedTools,
            IntentDefinition intentDefinition
    ) {
        this(intentId, confidence, meetsThreshold, acceptedTools, intentDefinition, null);
    }

    public ResolvedIntent(
            String intentId,
            double confidence,
            boolean meetsThreshold,
            List<Tool> acceptedTools
    ) {
        this(intentId, confidence, meetsThreshold, acceptedTools, null);
    }

    public ResolvedIntent {
        acceptedTools = acceptedTools != null ? List.copyOf(acceptedTools) : List.of();
    }

    public boolean isDegraded() {
        return degradedNotice != null;
    }

    public List<Tool> tools() {
        return acceptedTools;
    }

    public List<Tool> filteredTools() {
        return acceptedTools;
    }
}
