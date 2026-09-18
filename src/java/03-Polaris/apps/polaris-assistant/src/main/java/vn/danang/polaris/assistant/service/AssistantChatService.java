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
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.mcp.McpHub;
import vn.danang.polaris.assistant.mcp.ToolExecutionContext;
import vn.danang.polaris.assistant.mcp.ToolExecutionResult;
import vn.danang.polaris.assistant.model.AssistantModelClient;
import vn.danang.polaris.assistant.model.ModelRequestContext;
import vn.danang.polaris.assistant.model.ModelResponse;
import vn.danang.polaris.assistant.observability.AgentDecisionRecorder;

/**
 * Orchestrates the conversational agent workflow for Polaris Assistant.
 * Responsible for managing the conversation lifecycle, intent resolution,
 * ReAct workflow iterations, and delegating tool call batches to {@link McpHub}.
 */
@Service
@Transactional
public class AssistantChatService {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatService.class);
    private static final int MAX_TOOL_ITERATIONS = 5;

    private final AssistantModelClient modelClient;
    @Nullable
    private final McpHub mcpHub;
    private final ObjectMapper objectMapper;
    @Nullable
    private final Tracer tracer;
    private final AgentDecisionRecorder decisionRecorder;
    private final IntentResolver intentResolver;
    private final IntentToolRegistry intentToolRegistry;
    @Nullable
    private final PolicyEngine policyEngine;
    // In-memory conversation store: sessionId -> List of AssistantMessage
    private final Map<String, List<AssistantMessage>> conversationStore = new ConcurrentHashMap<>();

    @Autowired
    public AssistantChatService(
            AssistantModelClient modelClient,
            @Nullable McpHub mcpHub,
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
            // Process the request.
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
        String rawMessage = request.message();
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }
        String messageText = rawMessage.trim();

        String sessionId = (request.sessionId() != null && !request.sessionId().isBlank())
                ? request.sessionId()
                : UUID.randomUUID().toString();

        // 1. Load conversation history for this session
        List<AssistantMessage> history = conversationStore.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());

        // 2. Append incoming user message
        history.add(AssistantMessage.of(messageText));

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

        // Record user's intent
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

        // 5. Autonomous agent workflow loop (ReAct loop)
        int iterations = 0;

        while (finalReply == null && iterations < MAX_TOOL_ITERATIONS) {
            iterations++;
            recordEvent(span, "agent.iteration.started");
            log.info("Executing conversation turn iteration {} for sessionId: {}, userId: {}", iterations, sessionId, userId);

            ModelRequestContext context = new ModelRequestContext(
                    iterations,
                    intentId,
                    confidence,
                    filteredTools.size()
            );

            recordEvent(span, "model.request");
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
                ToolExecutionContext toolContext = new ToolExecutionContext(
                        sessionId,
                        userId,
                        iterations,
                        intentId,
                        confidence,
                        meetsThreshold,
                        filteredTools,
                        span,
                        this.policyEngine,
                        this.intentToolRegistry,
                        this.decisionRecorder
                );
                ToolExecutionResult toolResult = mcpHub.handleToolCalls(modelResponse.toolCalls(), toolContext);
                if (toolResult != null) {
                    if (toolResult.messages() != null) {
                        history.addAll(toolResult.messages());
                    }
                    if (toolResult.policyDenied()) {
                        finalReply = "Action denied: " + (toolResult.denialReason() != null ? toolResult.denialReason() : "Authorization required.");
                        break;
                    }
                }
            } else {
                finalReply = modelResponse.text();
                finalThoughtSignature = modelResponse.thoughtSignature();
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
}
