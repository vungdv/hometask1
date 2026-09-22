package vn.danang.polaris.assistant.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import vn.danang.polaris.assistant.intent.DefaultPolicyEngine;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyDecision;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.model.ToolCall;

/**
 * Hub and tool registry for Polaris Assistant.
 * Implements {@link McpHub} to discover tools and handle the tool call execution loop,
 * dispatching tool calls directly to Polaris Core via {@link PolarisMcpClient}
 * with distributed tracing and policy validation.
 */
@Component
public class ExternalMcpHub implements McpHub {

    private static final Logger log = LoggerFactory.getLogger(ExternalMcpHub.class);

    private final PolarisMcpClient polarisMcpClient;
    private final ObjectMapper objectMapper;
    @Nullable
    private final Tracer tracer;
    private final IntentToolRegistry intentToolRegistry;
    private final PolicyEngine policyEngine;

    @Autowired
    public ExternalMcpHub(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<IntentToolRegistry> intentToolRegistryProvider,
            ObjectProvider<PolicyEngine> policyEngineProvider) {
        this.polarisMcpClient = polarisMcpClient;
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
        this.objectMapper = objectMapperProvider != null && objectMapperProvider.getIfAvailable() != null
                ? objectMapperProvider.getIfAvailable()
                : new ObjectMapper();
        this.intentToolRegistry = intentToolRegistryProvider != null && intentToolRegistryProvider.getIfAvailable() != null
                ? intentToolRegistryProvider.getIfAvailable()
                : new IntentToolRegistry();
        this.policyEngine = policyEngineProvider != null && policyEngineProvider.getIfAvailable() != null
                ? policyEngineProvider.getIfAvailable()
                : new DefaultPolicyEngine();
    }

    public ExternalMcpHub(PolarisMcpClient polarisMcpClient, ObjectProvider<Tracer> tracerProvider) {
        this(polarisMcpClient, tracerProvider, null, null, null);
    }

    public ExternalMcpHub(PolarisMcpClient polarisMcpClient) {
        this(polarisMcpClient, (Tracer) null);
    }

    public ExternalMcpHub(PolarisMcpClient polarisMcpClient, @Nullable Tracer tracer) {
        this(polarisMcpClient, tracer, null, null, null);
    }

    public ExternalMcpHub(
            PolarisMcpClient polarisMcpClient,
            @Nullable Tracer tracer,
            @Nullable ObjectMapper objectMapper,
            @Nullable IntentToolRegistry intentToolRegistry,
            @Nullable PolicyEngine policyEngine) {
        this.polarisMcpClient = polarisMcpClient;
        this.tracer = tracer;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.intentToolRegistry = intentToolRegistry != null
                ? intentToolRegistry
                : new IntentToolRegistry();
        this.policyEngine = policyEngine != null
                ? policyEngine
                : new DefaultPolicyEngine();
    }

    /**
     * Discovers all tools available from Polaris Core.
     * Records a child distributed trace span ('mcp.list_tools') with provider and tool count tags.
     */
    @Override
    public List<Tool> discoverAllTools() {
        if (this.tracer == null) {
            return polarisMcpClient.listAvailableTools();
        }

        String spanName = "mcp.list_tools";
        Span span = this.tracer.nextSpan().name(spanName);
        span.tag("mcp.provider", "polaris-core");
        span.tag("mcp.operation", "tools/list");
        span.start();

        try (Tracer.SpanInScope ws = this.tracer.withSpan(span)) {
            List<Tool> tools = polarisMcpClient.listAvailableTools();
            if (tools != null) {
                span.tag("mcp.tools.count", String.valueOf(tools.size()));
            }
            return tools;
        } catch (Exception ex) {
            span.error(ex);
            span.tag("error", "true");
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Dispatches a tool invocation to Polaris Core.
     * Records a child distributed trace span ('mcp.tool_call <tool_name>') with provider, tool, and status tags.
     */
    @Override
    public CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        log.info("Dispatching execution for tool: {}", toolName);

        if (this.tracer == null) {
            return polarisMcpClient.callTool(toolName, arguments);
        }

        String spanName = "mcp.tool_call %s".formatted(Optional.ofNullable(toolName).orElse("unknown"));
        Span span = this.tracer.nextSpan().name(spanName);
        if (toolName != null) {
            span.tag("gen_ai.tool.name", toolName);
        }
        span.tag("mcp.provider", "polaris-core");
        span.tag("mcp.operation", "tools/call");
        span.start();

        try (Tracer.SpanInScope ws = this.tracer.withSpan(span)) {
            CallToolResult result = polarisMcpClient.callTool(toolName, arguments);
            if (result != null && Boolean.TRUE.equals(result.isError())) {
                span.tag("error", "true");
            }
            return result;
        } catch (Exception ex) {
            span.error(ex);
            span.tag("error", "true");
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Handles the execution for a batch of tool calls proposed by the AI model.
     * Validates intent, authorizes scopes against policy, executes tools via MCP,
     * emits lifecycle telemetry, records decision audits, and returns a list of {@link ToolResult}s.
     */
    @Override
    public List<ToolResult> handleToolCalls(List<ToolCall> toolCalls, ToolExecutionContext context) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        String sessionId = context != null && context.sessionId() != null ? context.sessionId() : "";
        String userId = context != null && context.userId() != null ? context.userId() : "anonymous";
        int iteration = context != null ? context.iteration() : 1;
        String intentId = context != null && context.intentId() != null ? context.intentId() : "general.conversation";
        boolean meetsThreshold = context != null && context.meetsThreshold();
        List<Tool> filteredTools = context != null && context.filteredTools() != null ? context.filteredTools() : List.of();
        Span span = Optional.ofNullable(tracer).map(Tracer::currentSpan).orElse(null);

        List<ToolResult> results = new ArrayList<>();

        for (ToolCall toolCall : toolCalls) {
            log.info("Model requested tool call: '{}' with arguments: {}", toolCall.name(), toolCall.arguments());
            logThoughtSignatureSampling(sessionId, iteration, toolCall.thoughtSignature(), span);

            // 1. Defensive tool validation against intent
            boolean isValidTool = meetsThreshold
                    ? this.intentToolRegistry.isValid(intentId, toolCall.name())
                    : filteredTools.stream().anyMatch(t -> t.name().equals(toolCall.name()));

            if (!isValidTool) {
                log.warn("Tool '{}' is not permitted for intent '{}'", toolCall.name(), intentId);
                String correctiveMessage = "Tool execution denied: Tool '" + toolCall.name() + "' is not permitted for intent '" + intentId + "'. Please provide a direct response or use permitted tools.";
                String semanticNote = "Tool '" + toolCall.name() + "' is not permitted under intent '" + intentId + "'.";
                results.add(ToolResult.error(toolCall, correctiveMessage, semanticNote));
                continue;
            }

            // 2. Defensive policy authorization against caller scopes
            String requiredScope = this.intentToolRegistry.getRequiredScope(toolCall.name());
            PolicyDecision policyDecision = this.policyEngine.authorize(userId, requiredScope);

            if (!policyDecision.allowed()) {
                log.warn("Policy DENIED execution of tool '{}' for user '{}': {}", toolCall.name(), userId, policyDecision.reason());
                String denialReason = policyDecision.reason() != null ? policyDecision.reason() : "Authorization required.";
                String semanticNote = "Policy authorization denied execution of tool '" + toolCall.name() + "' for user '" + userId + "': " + denialReason;
                results.add(ToolResult.denied(toolCall, denialReason, semanticNote));
                break;
            }

            // 3. Execute tool via MCP
            recordEvent(span, "agent.tool.call: " + toolCall.name());
            CallToolResult toolResult = executeTool(toolCall.name(), toolCall.arguments());
            String resultText = extractToolResultText(toolResult);
            recordEvent(span, "agent.tool.result: " + toolCall.name());

            if (toolResult != null && Boolean.TRUE.equals(toolResult.isError())) {
                String semanticNote = "MCP provider reported execution error for tool '" + toolCall.name() + "'.";
                results.add(ToolResult.error(toolCall, resultText, semanticNote));
            } else {
                results.add(ToolResult.success(toolCall, resultText));
            }
        }

        return results;
    }

    private void recordEvent(@Nullable Span span, String eventName) {
        Span targetSpan = span != null ? span : Optional.ofNullable(tracer).map(Tracer::currentSpan).orElse(null);
        if (targetSpan != null) {
            targetSpan.event(eventName);
        }
    }

    private String extractToolResultText(CallToolResult result) {
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
        return sb.toString();
    }

    private void logThoughtSignatureSampling(String sessionId, int iteration, String thoughtSignature, @Nullable Span span) {
        if (thoughtSignature == null || thoughtSignature.isBlank()) {
            return;
        }
        // 1% deterministic sampling based on sessionId hash, or always when DEBUG enabled
        boolean isSampled = log.isDebugEnabled() || (sessionId != null && Math.abs(sessionId.hashCode()) % 100 == 0);
        if (!isSampled) {
            return;
        }

        Span activeSpan = span != null ? span : Optional.ofNullable(tracer).map(Tracer::currentSpan).orElse(null);
        String traceId = "00000000000000000000000000000000";
        String spanId = "0000000000000000";
        if (activeSpan != null && activeSpan.context() != null) {
            if (activeSpan.context().traceId() != null) {
                traceId = activeSpan.context().traceId();
            }
            if (activeSpan.context().spanId() != null) {
                spanId = activeSpan.context().spanId();
            }
        }

        try {
            Map<String, Object> logPayload = Map.of(
                    "event", "thought_signature_sampled",
                    "trace_id", traceId,
                    "span_id", spanId,
                    "session_id", sessionId,
                    "iteration", iteration,
                    "thought_signature", thoughtSignature
            );
            log.info("Agent reasoning [THOUGHT]: {}", objectMapper.writeValueAsString(logPayload));
        } catch (Exception e) {
            log.debug("Failed to serialize thought signature sample log: {}", e.getMessage());
        }
    }
}
