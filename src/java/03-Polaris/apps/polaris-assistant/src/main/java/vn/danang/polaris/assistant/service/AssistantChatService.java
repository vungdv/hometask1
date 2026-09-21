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
import vn.danang.polaris.assistant.intent.IntentResolutionFacade;
import vn.danang.polaris.assistant.intent.IntentResolver;
import vn.danang.polaris.assistant.intent.IntentTaxonomyProperties;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.mcp.McpHub;
import vn.danang.polaris.assistant.mcp.ToolExecutionContext;
import vn.danang.polaris.assistant.mcp.ToolExecutionResult;
import vn.danang.polaris.assistant.model.AssistantModelClient;
import vn.danang.polaris.assistant.model.ModelRequestContext;
import vn.danang.polaris.assistant.model.ModelResponse;
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
    private final McpHub mcpHub;
    private final Optional<Tracer> tracer;
    private final IntentResolutionFacade intentResolutionFacade;
    // In-memory conversation store: sessionId -> List of AssistantMessage
    private final Map<String, List<AssistantMessage>> conversationStore = new ConcurrentHashMap<>();

    @Autowired
    public AssistantChatService(
            AssistantModelClient modelClient,
            McpHub mcpHub,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade) {
        this.modelClient = modelClient;
        this.mcpHub = mcpHub;
        this.tracer = Optional.ofNullable(tracerProvider).map(ObjectProvider::getIfAvailable);
        this.intentResolutionFacade = intentResolutionFacade != null
                ? intentResolutionFacade
                : new IntentResolutionFacade();
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
        String messageText = request.message().trim();

        String sessionId = (request.sessionId() != null && !request.sessionId().isBlank())
                ? request.sessionId()
                : "sess-" + System.currentTimeMillis();

        // 1. Get or create history for session
        List<AssistantMessage> history = conversationStore.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());

        // 2. Append incoming user message to history
        var userMsg = AssistantMessage.of(messageText);
        history.add(userMsg);

        // 3. Resolve intent and accepted tools via facade
        ResolvedIntent resolved = intentResolutionFacade.resolve(messageText, history);

        String intentId = resolved.intentId();
        double confidence = resolved.confidence();
        boolean meetsThreshold = resolved.meetsThreshold();
        List<Tool> filteredTools = resolved.acceptedTools();
        String finalReply = null;
        String finalThoughtSignature = null;

        // 5. Autonomous agent workflow loop (ReAct loop)
        int iterations = 0;

        while (iterations < MAX_TOOL_ITERATIONS) {
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

            if (modelResponse.hasToolCalls()) {
                ToolExecutionContext toolContext = new ToolExecutionContext(
                        sessionId,
                        userId,
                        iterations,
                        intentId,
                        confidence,
                        meetsThreshold,
                        filteredTools
                );
                ToolExecutionResult toolResult = mcpHub.handleToolCalls(modelResponse.toolCalls(), toolContext);
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
                break;
            }
        }

        if (finalReply == null || finalReply.isBlank()) {
            finalReply = "I have completed processing your request.";
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
