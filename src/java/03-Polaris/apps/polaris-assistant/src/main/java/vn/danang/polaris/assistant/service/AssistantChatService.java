package vn.danang.polaris.assistant.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import vn.danang.polaris.assistant.intent.IntentResolutionFacade;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.mcp.ToolExecutionContext;
import vn.danang.polaris.assistant.mcp.ToolResult;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

/**
 * Orchestrates the conversational agent main workflow for Polaris Assistant.
 * Coordinates conversation turn lifecycle, tool discovery, intent resolution,
 * and the reactive execution loop as specified in the assistant orchestrator design.
 */
@Service
@Transactional
public class AssistantChatService {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatService.class);
    private static final int MAX_TOOL_ITERATIONS = 5;
    private static final String DEFAULT_COMPLETION_REPLY = "I have completed processing your request.";

    private final AssistantModelClient modelClient;
    private final Optional<Tracer> tracer;
    private final IntentResolutionFacade intentResolutionFacade;
    private final ObjectMapper objectMapper;

    // In-memory conversation store: sessionId -> List of AssistantMessage
    private final Map<String, List<AssistantMessage>> conversationStore = new ConcurrentHashMap<>();

    @Autowired
    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tracer = Optional.ofNullable(tracerProvider).map(ObjectProvider::getIfAvailable);
        this.intentResolutionFacade = Objects.requireNonNull(intentResolutionFacade, "intentResolutionFacade must not be null");
        this.objectMapper = objectMapperProvider != null && objectMapperProvider.getIfAvailable() != null
                ? objectMapperProvider.getIfAvailable()
                : new ObjectMapper();
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade,
            @Nullable ObjectMapper objectMapper) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tracer = Optional.ofNullable(tracerProvider).map(ObjectProvider::getIfAvailable);
        this.intentResolutionFacade = Objects.requireNonNull(intentResolutionFacade, "intentResolutionFacade must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade) {
        this(modelClient, tracerProvider, intentResolutionFacade, (ObjectMapper) null);
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

        // 1. Load session history & append user message
        List<AssistantMessage> history = loadSessionHistory(sessionId);
        history.add(toUserTurn(messageText));

        // 2. Resolve intent & narrow tools to use.
        ResolvedIntent resolvedIntent = intentResolutionFacade.resolve(messageText, history);
        List<Tool> tools = resolvedIntent.acceptedTools();

        // 3. Execute tool loop (max 5 iterations)
        ConversationLoopResult loopResult = executeConversationLoop(sessionId, userId, history, tools, resolvedIntent);

        // 4. Tag iteration count on active span
        tagIterationsCount(loopResult.iterations());

        // 5. Append final assistant response to history
        AssistantMessage assistantMsg = toAssistantTurn(loopResult.reply(), loopResult.thoughtSignature());
        history.add(assistantMsg);

        // 6. Return response to controller
        return new ChatMessageResponse(
                sessionId,
                MessageRole.ASSISTANT.name(),
                loopResult.reply(),
                assistantMsg.getCreatedAt()
        );
    }

    private ConversationLoopResult executeConversationLoop(
            String sessionId,
            String userId,
            List<AssistantMessage> history,
            List<Tool> tools,
            ResolvedIntent resolvedIntent) {

        int iterations = 0;
        String finalReply = null;
        String finalThoughtSignature = null;

        while (iterations < MAX_TOOL_ITERATIONS) {
            iterations++;
            log.info("Executing conversation turn iteration {} for sessionId: {}, userId: {}", iterations, sessionId, userId);

            ModelRequestContext context = new ModelRequestContext(
                    iterations,
                    resolvedIntent.intentId(),
                    resolvedIntent.confidence(),
                    tools.size()
            );

            ModelResponse modelResponse = queryModel(history, tools, context);

            if (modelResponse.hasToolCalls()) {
                ToolExecutionOutcome outcome = executeToolBatch(sessionId, userId, iterations, resolvedIntent, modelResponse.toolCalls());
                history.addAll(outcome.turns());

                if (outcome.policyDenied()) {
                    finalReply = outcome.denialMessage();
                    break;
                }
            } else {
                finalReply = modelResponse.text();
                finalThoughtSignature = modelResponse.thoughtSignature();
                break;
            }
        }

        if (finalReply == null || finalReply.isBlank()) {
            finalReply = DEFAULT_COMPLETION_REPLY;
        }

        return new ConversationLoopResult(finalReply, finalThoughtSignature, iterations);
    }

    private ToolExecutionOutcome executeToolBatch(
            String sessionId,
            String userId,
            int iteration,
            ResolvedIntent resolvedIntent,
            List<ToolCall> toolCalls) {

        List<AssistantMessage> turns = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            turns.add(toModelTurn(toolCall));
        }

        ToolExecutionContext toolContext = new ToolExecutionContext(
                sessionId,
                userId,
                iteration,
                resolvedIntent.intentId(),
                resolvedIntent.confidence(),
                resolvedIntent.meetsThreshold(),
                resolvedIntent.acceptedTools()
        );

        List<ToolResult> toolResults = intentResolutionFacade.executeToolCalls(toolCalls, toolContext);
        boolean policyDenied = false;
        String denialMessage = null;

        if (toolResults != null) {
            for (ToolResult result : toolResults) {
                turns.add(toToolTurn(result));
                if (result.isDenied()) {
                    String reason = (result.result() != null && !result.result().isBlank())
                            ? result.result()
                            : "Authorization required.";
                    denialMessage = "Action denied: " + reason;
                    policyDenied = true;
                    break;
                }
            }
        }

        return new ToolExecutionOutcome(turns, policyDenied, denialMessage);
    }

    private ModelResponse queryModel(List<AssistantMessage> history, List<Tool> tools, ModelRequestContext context) {
        ModelResponse modelResponse = modelClient.generateResponse(new ArrayList<>(history), tools, context);
        if (modelResponse == null) {
            modelResponse = modelClient.generateResponse(new ArrayList<>(history), tools);
        }
        if (modelResponse == null) {
            String fallback = modelClient.chat(new ArrayList<>(history));
            modelResponse = new ModelResponse(fallback != null ? fallback : "");
        }
        return modelResponse;
    }

    private List<AssistantMessage> loadSessionHistory(String sessionId) {
        return conversationStore.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
    }

    private void tagIterationsCount(int iterations) {
        currentSpan().ifPresent(span -> span.tag("agent.iterations.count", String.valueOf(iterations)));
    }

    private Optional<Span> currentSpan() {
        return tracer.map(Tracer::currentSpan);
    }

    private AssistantMessage toUserTurn(String messageText) {
        return AssistantMessage.of(messageText);
    }

    private AssistantMessage toModelTurn(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        AssistantMessage modelTurn = new AssistantMessage();
        modelTurn.setRole(MessageRole.ASSISTANT);
        modelTurn.setToolCallId(toolCall.name());
        try {
            modelTurn.setWidgetPayload(objectMapper.writeValueAsString(toolCall.args()));
        } catch (Exception e) {
            modelTurn.setWidgetPayload("{}");
        }
        modelTurn.setThoughtSignature(toolCall.thoughtSignature());
        modelTurn.setCreatedAt(Instant.now());
        return modelTurn;
    }

    private AssistantMessage toToolTurn(ToolResult toolResult) {
        Objects.requireNonNull(toolResult, "toolResult must not be null");
        AssistantMessage toolTurn = new AssistantMessage();
        toolTurn.setRole(MessageRole.TOOL);
        toolTurn.setToolCallId(toolResult.toolCall().name());
        toolTurn.setContent(toolResult.result());
        toolTurn.setCreatedAt(Instant.now());
        return toolTurn;
    }

    private AssistantMessage toAssistantTurn(String reply, @Nullable String thoughtSignature) {
        return AssistantMessage.of(reply, MessageRole.ASSISTANT, thoughtSignature);
    }

    private record ConversationLoopResult(String reply, @Nullable String thoughtSignature, int iterations) {}
}
