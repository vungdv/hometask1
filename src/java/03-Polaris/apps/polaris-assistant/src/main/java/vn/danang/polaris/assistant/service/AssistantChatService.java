package vn.danang.polaris.assistant.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.DefaultIntentResolver;
import vn.danang.polaris.assistant.intent.DefaultPolicyEngine;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentResolver;
import vn.danang.polaris.assistant.intent.IntentTaxonomyProperties;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyDecision;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.mcp.ExternalMcpHub;
import vn.danang.polaris.assistant.model.AssistantModelClient;
import vn.danang.polaris.assistant.model.ModelRequestContext;
import vn.danang.polaris.assistant.model.ModelResponse;
import vn.danang.polaris.assistant.model.ToolCall;
import vn.danang.polaris.assistant.observability.AgentDecisionRecorder;

@Service
@Transactional
public class AssistantChatService {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatService.class);
    private static final int MAX_TOOL_ITERATIONS = 5;

    private final AssistantModelClient modelClient;
    private final ExternalMcpHub mcpHub;
    private final ObjectMapper objectMapper;
    @Nullable
    private final Tracer tracer;
    private final AgentDecisionRecorder decisionRecorder;
    private final IntentResolver intentResolver;
    private final IntentToolRegistry intentToolRegistry;
    private final PolicyEngine policyEngine;
    // In-memory conversation store: sessionId -> List of AssistantMessage
    private final Map<String, List<AssistantMessage>> conversationStore = new ConcurrentHashMap<>();

    @Autowired
    public AssistantChatService(
            AssistantModelClient modelClient,
            ExternalMcpHub mcpHub,
            ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<AgentDecisionRecorder> decisionRecorderProvider,
            ObjectProvider<IntentResolver> intentResolverProvider,
            ObjectProvider<IntentToolRegistry> intentToolRegistryProvider,
            ObjectProvider<PolicyEngine> policyEngineProvider) {
        this.modelClient = modelClient;
        this.mcpHub = mcpHub;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
        this.decisionRecorder = decisionRecorderProvider != null && decisionRecorderProvider.getIfAvailable() != null
                ? decisionRecorderProvider.getIfAvailable()
                : new AgentDecisionRecorder(this.objectMapper, this.tracer);
        this.intentToolRegistry = intentToolRegistryProvider != null && intentToolRegistryProvider.getIfAvailable() != null
                ? intentToolRegistryProvider.getIfAvailable()
                : new IntentToolRegistry();
        this.intentResolver = intentResolverProvider != null && intentResolverProvider.getIfAvailable() != null
                ? intentResolverProvider.getIfAvailable()
                : new DefaultIntentResolver(new IntentTaxonomyProperties());
        this.policyEngine = policyEngineProvider != null && policyEngineProvider.getIfAvailable() != null
                ? policyEngineProvider.getIfAvailable()
                : new DefaultPolicyEngine();
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ExternalMcpHub mcpHub,
            ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<AgentDecisionRecorder> decisionRecorderProvider) {
        this(modelClient, mcpHub, objectMapper, tracerProvider, decisionRecorderProvider, null, null, null);
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ExternalMcpHub mcpHub,
            ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracerProvider) {
        this(modelClient, mcpHub, objectMapper, tracerProvider, null);
    }

    public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub mcpHub, ObjectMapper objectMapper) {
        this(modelClient, mcpHub, objectMapper, (Tracer) null, (AgentDecisionRecorder) null);
    }

    public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub mcpHub) {
        this(modelClient, mcpHub, new ObjectMapper(), (Tracer) null, (AgentDecisionRecorder) null);
    }

    public AssistantChatService(AssistantModelClient modelClient) {
        this(modelClient, null, new ObjectMapper(), (Tracer) null, (AgentDecisionRecorder) null);
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ExternalMcpHub mcpHub,
            ObjectMapper objectMapper,
            @Nullable Tracer tracer) {
        this(modelClient, mcpHub, objectMapper, tracer, null);
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ExternalMcpHub mcpHub,
            ObjectMapper objectMapper,
            @Nullable Tracer tracer,
            @Nullable AgentDecisionRecorder decisionRecorder) {
        this(modelClient, mcpHub, objectMapper, tracer, decisionRecorder, null, null, null);
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ExternalMcpHub mcpHub,
            ObjectMapper objectMapper,
            @Nullable Tracer tracer,
            @Nullable AgentDecisionRecorder decisionRecorder,
            @Nullable IntentResolver intentResolver,
            @Nullable IntentToolRegistry intentToolRegistry,
            @Nullable PolicyEngine policyEngine) {
        this.modelClient = modelClient;
        this.mcpHub = mcpHub;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.tracer = tracer;
        this.decisionRecorder = decisionRecorder != null
                ? decisionRecorder
                : new AgentDecisionRecorder(this.objectMapper, this.tracer);
        this.intentToolRegistry = intentToolRegistry != null
                ? intentToolRegistry
                : new IntentToolRegistry();
        this.intentResolver = intentResolver != null
                ? intentResolver
                : new DefaultIntentResolver(new IntentTaxonomyProperties());
        this.policyEngine = policyEngine != null
                ? policyEngine
                : new DefaultPolicyEngine();
    }

    public ChatMessageResponse sendMessage(ChatMessageRequest request, String userId) {
        if (this.tracer == null) {
            return executeTurn(request, userId, null);
        }

        String sessionId = (request != null && request.sessionId() != null && !request.sessionId().isBlank())
                ? request.sessionId()
                : null;

        Span span = this.tracer.nextSpan().name("agent.turn");
        span.tag("agent.name", "assistant-chat");
        span.tag("agent.framework", "polaris-assistant");
        if (sessionId != null) {
            span.tag("agent.session_id", sessionId);
        }
        span.tag("agent.user_id", userId != null ? userId : "anonymous");
        span.start();

        try (Tracer.SpanInScope ws = this.tracer.withSpan(span)) {
            return executeTurn(request, userId, span);
        } catch (Exception ex) {
            span.error(ex);
            span.tag("error", "true");
            throw ex;
        } finally {
            span.end();
        }
    }

    private ChatMessageResponse executeTurn(ChatMessageRequest request, String userId, @Nullable Span span) {
        recordEvent(span, "agent.request.received");

        if (request == null) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }
        String messageText = request.resolvedMessage();
        if (messageText == null || messageText.isBlank()) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }

        String sessionId = (request.sessionId() != null && !request.sessionId().isBlank())
                ? request.sessionId()
                : UUID.randomUUID().toString();

        // 1. Load conversation history for this session
        List<AssistantMessage> history = conversationStore.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());

        // 2. Append incoming user message
        AssistantMessage userMsg = new AssistantMessage();
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(messageText);
        userMsg.setCreatedAt(Instant.now());
        history.add(userMsg);

        // 3. Discover available tools from MCP
        List<Tool> availableTools = (mcpHub != null) ? mcpHub.discoverAllTools() : List.of();
        if (availableTools == null) {
            availableTools = List.of();
        }
        recordEvent(span, "tools.discovered");
        if (span != null) {
            span.tag("agent.tools.count", String.valueOf(availableTools.size()));
        }

        // 4. Resolve intent once per user's turn
        IntentClassification classification = intentResolver.resolve(messageText, new ArrayList<>(history));
        String intentId = (classification != null && classification.intentId() != null)
                ? classification.intentId()
                : IntentClassification.GENERAL_CONVERSATION;
        double confidence = classification != null ? classification.confidence() : 1.0;
        double threshold = intentToolRegistry.getConfidenceThreshold(intentId);
        boolean meetsThreshold = confidence >= threshold;
        boolean isMutating = intentToolRegistry.isMutating(intentId);

        // record user's intent
        decisionRecorder.recordIntentResolution(sessionId, messageText, intentId, confidence, threshold, meetsThreshold, span);

        // Filter tools or prompt for clarification
        List<Tool> filteredTools;
        String finalReply = null;
        String finalThoughtSignature = null;

        if (!meetsThreshold && isMutating) {
            finalReply = "I noticed you may want to " + (IntentClassification.ORDER_CANCEL.equals(intentId) ? "cancel an order" : "place an order")
                    + ", but could you please clarify your request with specific details?";
            filteredTools = List.of();
            decisionRecorder.recordDirectResponseDecision(sessionId, intentId, confidence, availableTools, span);
        } else if (!meetsThreshold) {
            // Read-only intent with low confidence -> fall back to full available tools
            filteredTools = availableTools;
        } else {
            // Confidence meets threshold -> filter tools by intent
            filteredTools = intentToolRegistry.allowedTools(intentId, availableTools);
        }

        // 5. Autonomous tool execution loop (ReAct loop)
        int iterations = 0;

        while (finalReply == null && iterations < MAX_TOOL_ITERATIONS) {
            iterations++;
            recordEvent(span, "agent.iteration.started");
            log.info("Executing conversation turn iteration {} for sessionId: {}, userId: {}", iterations, sessionId, userId);

            recordEvent(span, "model.request");
            // Agent's intent: reasoning
            ModelRequestContext context = new ModelRequestContext(
                    iterations,
                    intentId,
                    confidence,
                    filteredTools.size()
            );
            ModelResponse modelResponse = modelClient.generateResponse(new ArrayList<>(history), filteredTools, context);
            if (modelResponse == null) {
                modelResponse = modelClient.generateResponse(new ArrayList<>(history), filteredTools);
            }
            if (modelResponse == null) {
                String fallbackText = modelClient.chat(new ArrayList<>(history));
                modelResponse = new ModelResponse(fallbackText != null ? fallbackText : "");
            }
            recordEvent(span, "model.response");

            if (modelResponse.hasToolCalls() && mcpHub != null) {
                // Agent's intent: something? that lead to some tool_calls
                List<AssistantMessage> modelTurns = new ArrayList<>();
                List<AssistantMessage> toolTurns = new ArrayList<>();
                boolean policyDenied = false;

                for (ToolCall toolCall : modelResponse.toolCalls()) {
                    log.info("Model requested tool call: '{}' with arguments: {}", toolCall.name(), toolCall.arguments());

                    // Save model's tool call turn to message history
                    AssistantMessage modelTurn = new AssistantMessage();
                    modelTurn.setRole(MessageRole.ASSISTANT);
                    modelTurn.setToolCallId(toolCall.name());
                    try {
                        modelTurn.setWidgetPayload(objectMapper.writeValueAsString(toolCall.arguments()));
                    } catch (Exception e) {
                        modelTurn.setWidgetPayload("{}");
                    }
                    modelTurn.setThoughtSignature(toolCall.thoughtSignature());
                    modelTurn.setCreatedAt(Instant.now());
                    modelTurns.add(modelTurn);

                    logThoughtSignatureSampling(sessionId, iterations, toolCall.thoughtSignature(), span);

                    // 1. Defensive tool validation against intent
                    boolean isValidTool = meetsThreshold
                            ? intentToolRegistry.isValid(intentId, toolCall.name())
                            : filteredTools.stream().anyMatch(t -> t.name().equals(toolCall.name()));

                    decisionRecorder.recordRegistryValidation(sessionId, intentId, toolCall.name(), isValidTool, span);

                    if (!isValidTool) {
                        log.warn("Tool '{}' is not permitted for intent '{}'", toolCall.name(), intentId);
                        AssistantMessage toolTurn = new AssistantMessage();
                        toolTurn.setRole(MessageRole.TOOL);
                        toolTurn.setToolCallId(toolCall.name());
                        toolTurn.setContent("Tool execution denied: Tool '" + toolCall.name() + "' is not permitted for intent '" + intentId + "'. Please provide a direct response or use permitted tools.");
                        toolTurn.setCreatedAt(Instant.now());
                        toolTurns.add(toolTurn);
                        continue;
                    }

                    // 2. Defensive policy authorization against caller scopes
                    String requiredScope = intentToolRegistry.getRequiredScope(toolCall.name());
                    PolicyDecision policyDecision = policyEngine.authorize(userId, requiredScope);
                    decisionRecorder.recordPolicyAuthorization(sessionId, intentId, toolCall.name(), requiredScope, policyDecision.allowed(), policyDecision.reason(), span);

                    if (!policyDecision.allowed()) {
                        log.warn("Policy DENIED execution of tool '{}' for user '{}': {}", toolCall.name(), userId, policyDecision.reason());
                        finalReply = "Action denied: " + (policyDecision.reason() != null ? policyDecision.reason() : "Authorization required.");
                        policyDenied = true;
                        break;
                    }

                    // 3. Execute tool via MCP with decision recording
                    recordEvent(span, "agent.tool.call: " + toolCall.name());
                    CallToolResult toolResult = decisionRecorder.recordToolExecution(
                            sessionId,
                            intentId,
                            confidence,
                            iterations,
                            toolCall.name(),
                            isValidTool,
                            policyDecision.allowed() ? "ALLOW" : "DENY",
                            policyDecision.reason(),
                            requiredScope,
                            toolCall.arguments(),
                            filteredTools,
                            span,
                            () -> mcpHub.executeTool(toolCall.name(), toolCall.arguments())
                    );
                    String resultText = extractToolResultText(toolResult);
                    recordEvent(span, "agent.tool.result: " + toolCall.name());

                    // Save tool execution result turn to message history
                    AssistantMessage toolTurn = new AssistantMessage();
                    toolTurn.setRole(MessageRole.TOOL);
                    toolTurn.setToolCallId(toolCall.name());
                    toolTurn.setContent(resultText);
                    toolTurn.setCreatedAt(Instant.now());
                    toolTurns.add(toolTurn);
                }

                history.addAll(modelTurns);
                history.addAll(toolTurns);

                if (policyDenied) {
                    break;
                }
            } else {
                finalReply = modelResponse.text();
                finalThoughtSignature = modelResponse.thoughtSignature();
                logThoughtSignatureSampling(sessionId, iterations, finalThoughtSignature, span);
                decisionRecorder.recordDirectResponseDecision(sessionId, intentId, confidence, filteredTools, span);
                break;
            }
        }

        if (finalReply == null || finalReply.isBlank()) {
            finalReply = "I have completed processing your request.";
            decisionRecorder.recordDirectResponseDecision(sessionId, intentId, confidence, filteredTools, span);
        }

        recordEvent(span, "agent.response.generated");
        if (span != null) {
            span.tag("agent.iterations.count", String.valueOf(iterations));
        }

        // 6. Append assistant reply to history
        AssistantMessage assistantMsg = new AssistantMessage();
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent(finalReply);
        assistantMsg.setThoughtSignature(finalThoughtSignature);
        assistantMsg.setCreatedAt(Instant.now());
        history.add(assistantMsg);

        recordEvent(span, "agent.completed");

        return new ChatMessageResponse(
                sessionId,
                MessageRole.ASSISTANT.name(),
                finalReply,
                assistantMsg.getCreatedAt()
        );
    }

    private void recordEvent(@Nullable Span span, String eventName) {
        if (span != null) {
            span.event(eventName);
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
        boolean isSampled = log.isDebugEnabled() || (Math.abs(sessionId.hashCode()) % 100 == 0);
        if (!isSampled) {
            return;
        }

        String traceId = "00000000000000000000000000000000";
        String spanId = "0000000000000000";
        if (span != null && span.context() != null) {
            if (span.context().traceId() != null) {
                traceId = span.context().traceId();
            }
            if (span.context().spanId() != null) {
                spanId = span.context().spanId();
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
