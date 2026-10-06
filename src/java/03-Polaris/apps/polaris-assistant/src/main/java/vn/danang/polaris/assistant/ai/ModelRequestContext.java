package vn.danang.polaris.assistant.ai;

import java.time.Instant;

import jakarta.annotation.Nullable;

/**
 * Immutable context describing the agent turn and intent state surrounding a model inference call.
 *
 * @param iteration the 1-based ReAct loop iteration
 * @param intentId the resolved intent identifier
 * @param intentConfidence the confidence score of intent resolution
 * @param toolsOfferedCount the number of tools filtered and offered to the model
 * @param conversationId the chat session the call belongs to ({@code gen_ai.conversation.id}); may be null
 * @param deadline when the turn's time budget runs out; the call must not wait past it. Null means no turn deadline
 */
public record ModelRequestContext(
        int iteration,
        String intentId,
        double intentConfidence,
        int toolsOfferedCount,
        String conversationId,
        @Nullable Instant deadline
) {
    public ModelRequestContext(int iteration, String intentId, double intentConfidence, int toolsOfferedCount,
            String conversationId) {
        this(iteration, intentId, intentConfidence, toolsOfferedCount, conversationId, null);
    }

    public ModelRequestContext(int iteration, String intentId, double intentConfidence, int toolsOfferedCount) {
        this(iteration, intentId, intentConfidence, toolsOfferedCount, null, null);
    }

    public static ModelRequestContext empty() {
        return new ModelRequestContext(1, "unknown", 1.0, 0);
    }
}
