package vn.danang.polaris.assistant.mcp;

import java.util.List;

import io.micrometer.tracing.Span;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.observability.AgentDecisionRecorder;

/**
 * Immutable context containing turn, intent, and tracing metadata required to execute and audit tool calls.
 *
 * @param sessionId the conversation session identifier
 * @param userId the caller user identity
 * @param iteration the 1-based ReAct loop iteration index
 * @param intentId the resolved intent identifier
 * @param confidence the confidence score of the resolved intent
 * @param meetsThreshold whether intent confidence meets the configured threshold
 * @param filteredTools the allowed tools for this turn
 * @param span the active distributed tracing span for the turn
 * @param policyEngine optional policy engine override from caller context
 * @param intentToolRegistry optional tool registry override from caller context
 * @param decisionRecorder optional decision recorder override from caller context
 */
public record ToolExecutionContext(
        String sessionId,
        String userId,
        int iteration,
        String intentId,
        double confidence,
        boolean meetsThreshold,
        List<Tool> filteredTools,
        @Nullable Span span,
        @Nullable PolicyEngine policyEngine,
        @Nullable IntentToolRegistry intentToolRegistry,
        @Nullable AgentDecisionRecorder decisionRecorder
) {
    public ToolExecutionContext(
            String sessionId,
            String userId,
            int iteration,
            String intentId,
            double confidence,
            boolean meetsThreshold,
            List<Tool> filteredTools,
            @Nullable Span span) {
        this(sessionId, userId, iteration, intentId, confidence, meetsThreshold, filteredTools, span, null, null, null);
    }
}
