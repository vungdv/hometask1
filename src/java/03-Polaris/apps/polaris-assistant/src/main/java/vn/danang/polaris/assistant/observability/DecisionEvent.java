package vn.danang.polaris.assistant.observability;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.micrometer.tracing.Span;
import jakarta.annotation.Nullable;

public record DecisionEvent(
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("span_id") String spanId,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("intent") String intent,
        @JsonProperty("evaluated_alternatives") List<EvaluatedAlternative> evaluatedAlternatives,
        @JsonProperty("selected_action") String selectedAction,
        @JsonProperty("confidence") Double confidence,
        @JsonProperty("policy_constraint") String policyConstraint,
        @JsonProperty("outcome") @JsonInclude(JsonInclude.Include.NON_NULL) Outcome outcome
) {
    public DecisionEvent withOutcome(Outcome outcome) {
        return new DecisionEvent(
                this.traceId, this.spanId, this.agentId, this.sessionId,
                this.timestamp, this.intent, this.evaluatedAlternatives,
                this.selectedAction, this.confidence, this.policyConstraint,
                outcome
        );
    }

    public void recordOn(@Nullable Span span) {
        if (span == null) return;
        if (selectedAction != null) span.tag("decision.action", selectedAction);
        if (intent != null) span.tag("decision.intent", intent);
        if (confidence != null) span.tag("decision.confidence", String.valueOf(confidence));
        if (policyConstraint != null) span.tag("decision.policy", policyConstraint);
        if (outcome != null) {
            if (outcome.status() != null) span.tag("decision.outcome.status", outcome.status());
            if (outcome.detail() != null) span.tag("decision.outcome.detail", outcome.detail());
            span.tag("decision.outcome.latency_ms", String.valueOf(outcome.latencyMs()));
        }
    }
}
