package vn.danang.polaris.assistant.observability;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;

/**
 * Component responsible for recording structured agent decision events,
 * capturing alternative evaluation, policy constraints, confidence scores,
 * emitting stateful JSON log lines, and attaching semantic decision tags
 * to the active distributed tracing span.
 */
@Component
public class AgentDecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentDecisionRecorder.class);
    private static final String DEFAULT_AGENT_ID = "polaris-assistant";
    private static final String DEFAULT_POLICY = "MAX_TOOL_ITERATIONS=5";
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_SPAN_ID = "0000000000000000";

    private final ObjectMapper objectMapper;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public AgentDecisionRecorder(ObjectMapper objectMapper, ObjectProvider<Tracer> tracerProvider) {
        this(objectMapper, tracerProvider != null ? tracerProvider.getIfAvailable() : null);
    }

    public AgentDecisionRecorder(ObjectMapper objectMapper) {
        this(objectMapper, (Tracer) null);
    }

    public AgentDecisionRecorder(ObjectMapper objectMapper, @Nullable Tracer tracer) {
        ObjectMapper mapper = objectMapper != null ? objectMapper.copy() : new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper = mapper;
        this.tracer = tracer;
    }

    /**
     * Records an autonomous direct response decision when no further tool executions are required.
     */
    public DecisionEvent recordDirectResponseDecision(
            String sessionId,
            String intent,
            List<Tool> availableTools) {
        return recordDirectResponseDecision(sessionId, intent, 1.0, availableTools, null);
    }

    public DecisionEvent recordDirectResponseDecision(
            String sessionId,
            String intent,
            List<Tool> availableTools,
            @Nullable Span span) {
        return recordDirectResponseDecision(sessionId, intent, 1.0, availableTools, span);
    }

    public DecisionEvent recordDirectResponseDecision(
            String sessionId,
            String intent,
            double confidence,
            List<Tool> availableTools) {
        return recordDirectResponseDecision(sessionId, intent, confidence, availableTools, (Span) null);
    }

    public DecisionEvent recordDirectResponseDecision(
            String sessionId,
            String intent,
            double confidence,
            List<Tool> availableTools,
            @Nullable Span span) {
        long startTime = System.currentTimeMillis();
        Span targetSpan = resolveSpan(span);

        List<EvaluatedAlternative> alternatives = buildEvaluatedAlternatives(availableTools, "reply_to_user", true);
        long latencyMs = System.currentTimeMillis() - startTime;
        Outcome outcome = new Outcome("SUCCESS", "Direct conversational response generated", latencyMs);

        DecisionEvent event = new DecisionEvent(
                resolveTraceId(targetSpan),
                resolveSpanId(targetSpan),
                DEFAULT_AGENT_ID,
                sessionId != null ? sessionId : "unknown",
                Instant.now(),
                intent != null ? intent : "",
                alternatives,
                "reply_to_user",
                confidence,
                DEFAULT_POLICY,
                outcome
        );

        logDecision("COMPLETED", event);
        event.recordOn(targetSpan);
        return event;
    }

    /**
     * Records intent resolution decision event and span tags using the ambient active span.
     */
    public DecisionEvent recordIntentResolution(
            String sessionId,
            String userMessage,
            String intentId,
            double confidence,
            double threshold,
            boolean resolved) {
        return recordIntentResolution(sessionId, userMessage, intentId, confidence, threshold, resolved, (Span) null);
    }

    /**
     * Records intent resolution decision event and span tags.
     */
    public DecisionEvent recordIntentResolution(
            String sessionId,
            String userMessage,
            String intentId,
            double confidence,
            double threshold,
            boolean resolved,
            @Nullable Span span) {
        Span targetSpan = resolveSpan(span);
        Outcome outcome = new Outcome(
                resolved ? "RESOLVED" : "LOW_CONFIDENCE",
                "Intent resolved with confidence " + confidence + " (threshold: " + threshold + ")",
                0
        );

        DecisionEvent event = new DecisionEvent(
                resolveTraceId(targetSpan),
                resolveSpanId(targetSpan),
                DEFAULT_AGENT_ID,
                sessionId != null ? sessionId : "unknown",
                Instant.now(),
                intentId != null ? intentId : "unknown",
                List.of(),
                "intent.resolve",
                confidence,
                "THRESHOLD=" + threshold,
                outcome
        );

        logDecision("COMPLETED", event);
        if (targetSpan != null) {
            targetSpan.tag("decision.action", "intent.resolve");
            targetSpan.tag("decision.intent", intentId != null ? intentId : "unknown");
            targetSpan.tag("decision.confidence", String.valueOf(confidence));
            targetSpan.tag("decision.outcome.status", resolved ? "RESOLVED" : "LOW_CONFIDENCE");
        }
        return event;
    }

    /**
     * Records tool registry validation defensive check.
     */
    public DecisionEvent recordRegistryValidation(
            String sessionId,
            String intentId,
            String toolName,
            boolean valid,
            @Nullable Span span) {
        Span targetSpan = resolveSpan(span);
        Outcome outcome = new Outcome(
                valid ? "VALID" : "TOOL_MISMATCH",
                valid ? "Tool is permitted for intent" : "Tool " + toolName + " is not permitted for intent " + intentId,
                0
        );

        DecisionEvent event = new DecisionEvent(
                resolveTraceId(targetSpan),
                resolveSpanId(targetSpan),
                DEFAULT_AGENT_ID,
                sessionId != null ? sessionId : "unknown",
                Instant.now(),
                intentId != null ? intentId : "unknown",
                List.of(),
                "registry.validate",
                1.0,
                "INTENT=" + intentId,
                outcome
        );

        logDecision(valid ? "COMPLETED" : "FAILED", event);
        if (targetSpan != null) {
            targetSpan.tag("decision.action", "registry.validate");
            targetSpan.tag("decision.outcome.status", valid ? "VALID" : "TOOL_MISMATCH");
        }
        return event;
    }

    /**
     * Records policy engine authorization check.
     */
    public DecisionEvent recordPolicyAuthorization(
            String sessionId,
            String intentId,
            String toolName,
            @Nullable String requiredScope,
            boolean allowed,
            @Nullable String reason,
            @Nullable Span span) {
        Span targetSpan = resolveSpan(span);
        Outcome outcome = new Outcome(
                allowed ? "ALLOW" : "DENY",
                reason != null ? reason : (allowed ? "Scope granted" : "Scope denied"),
                0
        );

        DecisionEvent event = new DecisionEvent(
                resolveTraceId(targetSpan),
                resolveSpanId(targetSpan),
                DEFAULT_AGENT_ID,
                sessionId != null ? sessionId : "unknown",
                Instant.now(),
                intentId != null ? intentId : "unknown",
                List.of(),
                "policy.authorize",
                1.0,
                requiredScope != null ? requiredScope : "none",
                outcome
        );

        logDecision(allowed ? "COMPLETED" : "FAILED", event);
        if (targetSpan != null) {
            targetSpan.tag("decision.action", "policy.authorize");
            targetSpan.tag("decision.policy", requiredScope != null ? requiredScope : "none");
            targetSpan.tag("decision.outcome.status", allowed ? "ALLOW" : "DENY");
        }
        return event;
    }

    /**
     * Records a tool execution decision, emitting STARTING before invocation and
     * COMPLETED, ERROR, or FAILED after execution.
     */
    public CallToolResult recordToolExecution(
            String sessionId,
            String intent,
            String toolName,
            List<Tool> availableTools,
            Supplier<CallToolResult> toolExecution) {
        return recordToolExecution(sessionId, intent, 1.0, toolName, availableTools, null, toolExecution);
    }

    public CallToolResult recordToolExecution(
            String sessionId,
            String intent,
            String toolName,
            List<Tool> availableTools,
            @Nullable Span span,
            Supplier<CallToolResult> toolExecution) {
        return recordToolExecution(sessionId, intent, 1.0, toolName, availableTools, span, toolExecution);
    }

    public CallToolResult recordToolExecution(
            String sessionId,
            String intent,
            double confidence,
            String toolName,
            List<Tool> availableTools,
            @Nullable Span span,
            Supplier<CallToolResult> toolExecution) {
        return recordToolExecution(
                sessionId,
                intent,
                confidence,
                1,
                toolName,
                true,
                "ALLOW",
                "Default policy allowed",
                "none",
                Map.of(),
                availableTools,
                span,
                toolExecution
        );
    }

    public CallToolResult recordToolExecution(
            String sessionId,
            String intent,
            double confidence,
            int iteration,
            String toolName,
            boolean validationResult,
            String policyDecision,
            @Nullable String policyReason,
            @Nullable String requiredScope,
            @Nullable Map<String, Object> arguments,
            List<Tool> availableTools,
            @Nullable Span span,
            Supplier<CallToolResult> toolExecution) {
        Span targetSpan = resolveSpan(span);
        List<EvaluatedAlternative> alternatives = buildEvaluatedAlternatives(availableTools, toolName, false);

        DecisionEvent startEvent = new DecisionEvent(
                resolveTraceId(targetSpan),
                resolveSpanId(targetSpan),
                DEFAULT_AGENT_ID,
                sessionId != null ? sessionId : "unknown",
                Instant.now(),
                intent != null ? intent : "",
                alternatives,
                toolName,
                confidence,
                DEFAULT_POLICY,
                null
        );

        logDecision("STARTING", startEvent);

        if (targetSpan != null) {
            targetSpan.tag("gen_ai.tool.name", toolName != null ? toolName : "unknown");
            targetSpan.tag("agent.iteration", String.valueOf(iteration));
            targetSpan.tag("agent.tool.validation_result", validationResult ? "VALID" : "TOOL_MISMATCH");
            targetSpan.tag("agent.policy.decision", policyDecision != null ? policyDecision : "ALLOW");
            targetSpan.tag("agent.policy.reason", policyReason != null ? policyReason : "None");
            targetSpan.tag("agent.policy.required_scope", requiredScope != null ? requiredScope : "none");
            String argsSummary = ArgumentSanitizer.sanitizeToSummary(arguments);
            targetSpan.tag("mcp.tool.args_summary", argsSummary);
        }

        long startNanos = System.nanoTime();
        try {
            CallToolResult result = toolExecution.get();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;

            int resultBytes = 0;
            if (result != null && result.content() != null) {
                for (var c : result.content()) {
                    if (c instanceof TextContent tc && tc.text() != null) {
                        resultBytes += tc.text().getBytes(StandardCharsets.UTF_8).length;
                    }
                }
            }
            if (targetSpan != null) {
                targetSpan.tag("agent.tool.result_size_bytes", String.valueOf(resultBytes));
            }

            if (result != null && result.isError()) {
                if (targetSpan != null) {
                    targetSpan.tag("error", "true");
                }
                String errorDetail = extractSummary(result);
                if (errorDetail.isBlank()) {
                    errorDetail = "Tool returned error result";
                }
                Outcome outcome = new Outcome("FAILURE", errorDetail, latencyMs);
                DecisionEvent errorEvent = startEvent.withOutcome(outcome);
                logDecision("ERROR", errorEvent);
                errorEvent.recordOn(targetSpan);
            } else {
                String summary = extractSummary(result);
                if (summary.isBlank()) {
                    summary = "Tool executed successfully";
                }
                Outcome outcome = new Outcome("SUCCESS", summary, latencyMs);
                DecisionEvent completedEvent = startEvent.withOutcome(outcome);
                logDecision("COMPLETED", completedEvent);
                completedEvent.recordOn(targetSpan);
            }
            return result;
        } catch (Exception ex) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (targetSpan != null) {
                targetSpan.tag("error", "true");
            }
            String errorMsg = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            Outcome outcome = new Outcome("FAILURE", errorMsg, latencyMs);
            DecisionEvent failedEvent = startEvent.withOutcome(outcome);
            logDecision("FAILED", failedEvent);
            failedEvent.recordOn(targetSpan);
            throw ex;
        }
    }

    /**
     * Builds the evaluated alternatives list from candidate tools.
     */
    public List<EvaluatedAlternative> buildEvaluatedAlternatives(
            List<Tool> availableTools,
            String selectedAction) {
        return buildEvaluatedAlternatives(availableTools, selectedAction, "reply_to_user".equals(selectedAction));
    }

    public List<EvaluatedAlternative> buildEvaluatedAlternatives(
            List<Tool> availableTools,
            String selectedAction,
            boolean isDirectResponse) {
        List<EvaluatedAlternative> alternatives = new ArrayList<>();
        List<Tool> safeTools = availableTools != null ? availableTools : List.of();

        if (isDirectResponse) {
            for (Tool tool : safeTools) {
                String desc = (tool.description() != null && !tool.description().isBlank())
                        ? tool.description()
                        : "Candidate tool in registry";
                alternatives.add(new EvaluatedAlternative(
                        tool.name(),
                        desc,
                        false,
                        "Alternative tool not selected for current intent"
                ));
            }
            alternatives.add(new EvaluatedAlternative(
                    "reply_to_user",
                    "Provide direct conversational answer to user",
                    true,
                    null
            ));
        } else {
            boolean found = false;
            for (Tool tool : safeTools) {
                boolean selected = tool.name().equals(selectedAction);
                if (selected) {
                    found = true;
                }
                String desc = (tool.description() != null && !tool.description().isBlank())
                        ? tool.description()
                        : "Candidate tool in registry";
                alternatives.add(new EvaluatedAlternative(
                        tool.name(),
                        desc,
                        selected,
                        selected ? null : "Alternative tool not selected for current intent"
                ));
            }
            if (!found && selectedAction != null) {
                alternatives.add(new EvaluatedAlternative(
                        selectedAction,
                        "Tool matching user intent",
                        true,
                        null
                ));
            }
        }
        return alternatives;
    }

    private void logDecision(String state, DecisionEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            log.info("Agent decision [{}]: {}", state, json);
        } catch (Exception e) {
            log.warn("Failed to serialize DecisionEvent for state [{}]: {}", state, e.getMessage());
        }
    }

    private String extractSummary(CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof TextContent textContent) {
                sb.append(textContent.text());
            } else if (content != null) {
                sb.append(content.toString());
            }
        }
        String text = sb.toString().trim();
        if (text.length() > 200) {
            return text.substring(0, 197) + "...";
        }
        return text;
    }

    @Nullable
    private Span resolveSpan(@Nullable Span span) {
        if (span != null) {
            return span;
        }
        return (tracer != null) ? tracer.currentSpan() : null;
    }

    private String resolveTraceId(@Nullable Span span) {
        Span active = resolveSpan(span);
        if (active != null && active.context() != null) {
            String traceId = active.context().traceId();
            if (traceId != null && !traceId.isBlank()) {
                return traceId;
            }
        }
        return ZERO_TRACE_ID;
    }

    private String resolveSpanId(@Nullable Span span) {
        Span active = resolveSpan(span);
        if (active != null && active.context() != null) {
            String spanId = active.context().spanId();
            if (spanId != null && !spanId.isBlank()) {
                return spanId;
            }
        }
        return ZERO_SPAN_ID;
    }
}
