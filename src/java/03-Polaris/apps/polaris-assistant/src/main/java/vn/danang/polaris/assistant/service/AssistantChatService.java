package vn.danang.polaris.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

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
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

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
    private final Optional<McpHub> mcpHub;
    private final ObjectMapper objectMapper;
    private final Optional<Tracer> tracer;
    private final AgentDecisionRecorder decisionRecorder;
    private final IntentResolver intentResolver;
    private final IntentToolRegistry intentToolRegistry;
    private final Optional<PolicyEngine> policyEngine;

    // In-memory conversation store: sessionId -> List of AssistantMessage
    private final Map<String, List<AssistantMessage>> conversationStore = new ConcurrentHashMap<>();

    @Autowired
    public AssistantChatService(
            AssistantModelClient modelClient,
            @Nullable McpHub mcpHub,
            @Nullable ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<AgentDecisionRecorder> decisionRecorderProvider,
            ObjectProvider<IntentResolver> intentResolverProvider,
            ObjectProvider<IntentToolRegistry> intentToolRegistryProvider,
            ObjectProvider<PolicyEngine> policyEngineProvider) {
        this.modelClient = modelClient;
        this.mcpHub = Optional.ofNullable(mcpHub);
        this.objectMapper = Optional.ofNullable(objectMapper).orElseGet(ObjectMapper::new);
        this.tracer = Optional.ofNullable(tracerProvider).map(ObjectProvider::getIfAvailable);
        this.decisionRecorder = Optional.ofNullable(decisionRecorderProvider)
                .map(ObjectProvider::getIfAvailable)
                .orElseGet(() -> new AgentDecisionRecorder(this.objectMapper, this.tracer.orElse(null)));
        this.intentToolRegistry = Optional.ofNullable(intentToolRegistryProvider)
                .map(ObjectProvider::getIfAvailable)
                .orElseGet(IntentToolRegistry::new);
        this.intentResolver = Optional.ofNullable(intentResolverProvider)
                .map(ObjectProvider::getIfAvailable)
                .orElseGet(() -> new DefaultIntentResolver(new IntentTaxonomyProperties()));
        this.policyEngine = Optional.ofNullable(policyEngineProvider).map(ObjectProvider::getIfAvailable);
    }

    @CustomNextSpan(
            name = "agent.turn",
            tags = {
                @SpanTag(key = "agent.name", value = "assistant-chat"),
                @SpanTag(key = "agent.framework", value = "polaris-assistant"),
                @SpanTag(key = "agent.session_id", expression = "#request?.sessionId()"),
                @SpanTag(key = "agent.user_id", expression = "#userId != null && !#userId.isBlank() ? #userId : 'anonymous'")
            }
    )
    public ChatMessageResponse sendMessage(ChatMessageRequest request, String userId) {
        String messageText = request.message();
        String sessionId = request.sessionId();

        // 1. Load conversation history for this session
        List<AssistantMessage> history = conversationStore.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());

        // 2. Append incoming user message
        history.add(AssistantMessage.of(messageText));

        // 3. Discover available tools from MCP
        List<Tool> availableTools = mcpHub
                .map(McpHub::discoverAllTools)
                .orElseGet(List::of);

        // 4. Resolve intent once per user's turn
        Optional<IntentClassification> classification = Optional.ofNullable(
                intentResolver.resolve(messageText, new ArrayList<>(history)));
        String intentId = classification
                .map(IntentClassification::intentId)
                .filter(Predicate.not(String::isBlank))
                .orElse(IntentClassification.GENERAL_CONVERSATION);
        double confidence = classification
                .map(IntentClassification::confidence)
                .orElse(1.0);

        double threshold = intentToolRegistry.getConfidenceThreshold(intentId);
        boolean meetsThreshold = confidence >= threshold;
        boolean isMutating = intentToolRegistry.isMutating(intentId);

        // Record user's intent
        decisionRecorder.recordIntentResolution(sessionId, messageText, intentId, confidence, threshold, meetsThreshold);

        // Filter tools or prompt for clarification
        List<Tool> filteredTools;
        String finalReply = null;
        String finalThoughtSignature = null;

        if (!meetsThreshold && isMutating) {
            finalReply = "I noticed you may want to " + (IntentClassification.ORDER_CANCEL.equals(intentId) ? "cancel an order" : "place an order")
                    + ", but could you please clarify your request with specific details?";
            filteredTools = List.of();
            decisionRecorder.recordDirectResponseDecision(sessionId, intentId, confidence, availableTools);
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
            log.info("Executing conversation turn iteration {} for sessionId: {}, userId: {}", iterations, sessionId, userId);

            ModelRequestContext context = new ModelRequestContext(
                    iterations,
                    intentId,
                    confidence,
                    filteredTools.size()
            );

            ModelResponse modelResponse = Optional.ofNullable(modelClient.generateResponse(new ArrayList<>(history), filteredTools, context))
                    .or(() -> Optional.ofNullable(modelClient.generateResponse(new ArrayList<>(history), filteredTools)))
                    .orElseGet(() -> {
                        String fallback = Optional.ofNullable(modelClient.chat(new ArrayList<>(history))).orElse("");
                        return new ModelResponse(fallback);
                    });

            if (modelResponse.hasToolCalls() && mcpHub.isPresent()) {
                ToolExecutionContext toolContext = new ToolExecutionContext(
                        sessionId,
                        userId,
                        iterations,
                        intentId,
                        confidence,
                        meetsThreshold,
                        filteredTools,
                        this.policyEngine.orElse(null),
                        this.intentToolRegistry,
                        this.decisionRecorder
                );
                ToolExecutionResult toolResult = mcpHub.get().handleToolCalls(modelResponse.toolCalls(), toolContext);
                if (toolResult != null) {
                    Optional.ofNullable(toolResult.messages()).ifPresent(history::addAll);
                    if (toolResult.policyDenied()) {
                        finalReply = "Action denied: " + Optional.ofNullable(toolResult.denialReason()).orElse("Authorization required.");
                        break;
                    }
                }
            } else {
                finalReply = modelResponse.text();
                finalThoughtSignature = modelResponse.thoughtSignature();
                decisionRecorder.recordDirectResponseDecision(sessionId, intentId, confidence, filteredTools);
                break;
            }
        }

        if (finalReply == null || finalReply.isBlank()) {
            finalReply = "I have completed processing your request.";
            decisionRecorder.recordDirectResponseDecision(sessionId, intentId, confidence, filteredTools);
        }

        tagIterationsCount(iterations);

        // 6. Append assistant reply to history
        var assistantMsg = AssistantMessage.of(finalReply, MessageRole.ASSISTANT, finalThoughtSignature);
        history.add(assistantMsg);

        return new ChatMessageResponse(
                sessionId,
                MessageRole.ASSISTANT.name(),
                finalReply,
                assistantMsg.getCreatedAt()
        );
    }

    private void tagIterationsCount(int iterations) {
        currentSpan().ifPresent(span -> span.tag("agent.iterations.count", String.valueOf(iterations)));
    }

    private Optional<Span> currentSpan() {
        return tracer.map(Tracer::currentSpan);
    }
}
