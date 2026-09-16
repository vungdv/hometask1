package vn.danang.polaris.assistant.observability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        return recordDirectResponseDecision(sessionId, intent, availableTools, null);
    }

    public DecisionEvent recordDirectResponseDecision(
            String sessionId,
            String intent,
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
                1.0,
                DEFAULT_POLICY,
                outcome
        );

        logDecision("COMPLETED", event);
        event.recordOn(targetSpan);
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
        return recordToolExecution(sessionId, intent, toolName, availableTools, null, toolExecution);
    }

    public CallToolResult recordToolExecution(
            String sessionId,
            String intent,
            String toolName,
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
                1.0,
                DEFAULT_POLICY,
                null
        );

        logDecision("STARTING", startEvent);

        long startNanos = System.nanoTime();
        try {
            CallToolResult result = toolExecution.get();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;

            if (result != null && result.isError()) {
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
