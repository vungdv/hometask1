package vn.danang.polaris.assistant.ai;

/**
 * Immutable context describing the agent turn and intent state surrounding a model inference call.
 *
 * @param iteration the 1-based ReAct loop iteration
 * @param intentId the resolved intent identifier
 * @param intentConfidence the confidence score of intent resolution
 * @param toolsOfferedCount the number of tools filtered and offered to the model
 * @param conversationId the chat session the call belongs to ({@code gen_ai.conversation.id}); may be null
 */
public record ModelRequestContext(
        int iteration,
        String intentId,
        double intentConfidence,
        int toolsOfferedCount,
        String conversationId
) {
    public ModelRequestContext(int iteration, String intentId, double intentConfidence, int toolsOfferedCount) {
        this(iteration, intentId, intentConfidence, toolsOfferedCount, null);
    }

    public static ModelRequestContext empty() {
        return new ModelRequestContext(1, "unknown", 1.0, 0);
    }
}
