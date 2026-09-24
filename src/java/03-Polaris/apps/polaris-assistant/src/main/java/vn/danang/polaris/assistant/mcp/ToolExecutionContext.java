package vn.danang.polaris.assistant.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.intent.DefaultIntentResolver;
import vn.danang.polaris.assistant.intent.IntentDefinition;
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

    private static final Map<String, IntentDefinition> DEFAULT_INTENTS = new ConcurrentHashMap<>();

    public ToolExecutionContext {
        if (resolvedIntent != null && resolvedIntent.intentDefinition() == null && resolvedIntent.intentId() != null) {
            IntentDefinition def = lookupDefaultIntent(resolvedIntent.intentId());
            if (def != null) {
                resolvedIntent = new ResolvedIntent(
                        resolvedIntent.intentId(),
                        resolvedIntent.confidence(),
                        resolvedIntent.meetsThreshold(),
                        resolvedIntent.acceptedTools(),
                        def
                );
            }
        }
    }

    public ToolExecutionContext(
            String sessionId,
            String userId,
            int iteration,
            String intentId,
            double confidence,
            boolean meetsThreshold,
            List<Tool> filteredTools
    ) {
        this(sessionId, userId, iteration, new ResolvedIntent(
                intentId,
                confidence,
                meetsThreshold,
                filteredTools,
                lookupDefaultIntent(intentId)
        ));
    }

    private static IntentDefinition lookupDefaultIntent(String id) {
        if (id == null) {
            return null;
        }
        if (DEFAULT_INTENTS.isEmpty()) {
            for (IntentDefinition def : DefaultIntentResolver.loadDefaultIntents()) {
                if (def.id() != null) {
                    DEFAULT_INTENTS.put(def.id(), def);
                }
            }
        }
        return DEFAULT_INTENTS.get(id);
    }
}
