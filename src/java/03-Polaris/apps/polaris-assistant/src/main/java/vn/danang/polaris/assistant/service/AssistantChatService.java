package vn.danang.polaris.assistant.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantSessionRepository;
import vn.danang.polaris.assistant.entity.AssistantSessionStatus;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.tools.StageOrderDraftTool;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolResult;
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
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final AssistantModelClient modelClient;
    private final Optional<Tracer> tracer;
    private final IntentResolutionFacade intentResolutionFacade;
    private final ObjectMapper objectMapper;
    @Nullable
    private final AssistantSessionRepository sessionRepository;
    private final ExecutorService streamingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // In-memory conversation store: sessionId -> List of AssistantMessage
    private final Map<String, List<AssistantMessage>> conversationStore = new ConcurrentHashMap<>();

    @Autowired
    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<AssistantSessionRepository> sessionRepositoryProvider) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tracer = Optional.ofNullable(tracerProvider).map(ObjectProvider::getIfAvailable);
        this.intentResolutionFacade = Objects.requireNonNull(intentResolutionFacade, "intentResolutionFacade must not be null");
        this.objectMapper = objectMapperProvider != null && objectMapperProvider.getIfAvailable() != null
                ? objectMapperProvider.getIfAvailable()
                : new ObjectMapper();
        this.sessionRepository = sessionRepositoryProvider != null ? sessionRepositoryProvider.getIfAvailable() : null;
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
        this.sessionRepository = null;
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
        // 3. Execute tool loop (max 5 iterations)
        ConversationLoopResult loopResult = executeConversationLoop(sessionId, userId, history, resolvedIntent);

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

    /**
     * SSE-emitting variant of the ReAct loop (ADR-0004 §1.A), backing the new
     * {@code POST /api/v1/assistant/sessions/{sessionId}/messages} endpoint. Reuses the exact same
     * intent-resolution/tool-execution machinery as {@link #sendMessage}; {@code sendMessage} itself
     * and {@code POST /api/v1/assistant/chat} are untouched by this method's existence.
     */
    public SseEmitter streamTurn(String sessionId, String content, String userId) {
        SseEmitter emitter = new SseEmitter(0L);
        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter), HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        Runnable stopHeartbeat = () -> {
            heartbeat.cancel(true);
            heartbeatExecutor.shutdownNow();
        };
        emitter.onCompletion(stopHeartbeat);
        emitter.onTimeout(stopHeartbeat);
        emitter.onError(ex -> stopHeartbeat.run());

        streamingExecutor.execute(() -> runStreamingTurn(emitter, sessionId, content, userId));
        return emitter;
    }

    private void sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException | IllegalStateException ex) {
            // Emitter already closed/completed; the onCompletion/onTimeout/onError callbacks
            // registered in streamTurn already cancel this scheduled task in that case.
        }
    }

    /** Package-visible (not private) so tests can drive it directly with a recording {@link SseEmitter}. */
    void runStreamingTurn(SseEmitter emitter, String sessionId, String content, String userId) {
        try {
            List<AssistantMessage> history = loadSessionHistory(sessionId);
            history.add(toUserTurn(content));

            ResolvedIntent resolvedIntent = intentResolutionFacade.resolve(content, history);

            String finalReply = null;
            String finalThoughtSignature = null;
            int iterations = 0;

            while (iterations < MAX_TOOL_ITERATIONS) {
                iterations++;
                sendEvent(emitter, "thought", Map.of("step", "iteration-" + iterations, "message", "Reasoning about the request..."));

                ModelRequestContext context = new ModelRequestContext(
                        iterations, resolvedIntent.intentId(), resolvedIntent.confidence(), resolvedIntent.acceptedTools().size());
                ModelResponse modelResponse = queryModel(history, resolvedIntent.acceptedTools(), context);

                if (modelResponse.hasToolCalls()) {
                    ToolExecutionOutcome outcome = executeToolBatch(sessionId, userId, iterations, resolvedIntent, modelResponse.toolCalls());
                    history.addAll(outcome.turns());
                    emitToolOutcomeEvents(emitter, outcome.toolResults());

                    if (outcome.policyDenied()) {
                        finalReply = outcome.denialMessage();
                        history.add(toAssistantTurn(finalReply, null));
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
            sendEvent(emitter, "token", Map.of("delta", finalReply));
            history.add(toAssistantTurn(finalReply, finalThoughtSignature));

            sendEvent(emitter, "done", Map.of("sessionId", sessionId, "status", currentSessionStatus(sessionId)));
            emitter.complete();
        } catch (Exception ex) {
            log.error("Streaming turn failed for session {}: {}", sessionId, ex.getMessage(), ex);
            emitter.completeWithError(ex);
        }
    }

    /**
     * Translates a batch's raw {@link ToolResult}s into the SSE {@code draft}/{@code problem}
     * frames ADR-0004 §1.A specifies, whenever a {@code stage_order_draft} call resolved to a
     * {@code Staged}/{@code Rejected} outcome (see {@code PolicyToolManager}'s {@code data}/
     * {@code actions} population for that tool).
     */
    private void emitToolOutcomeEvents(SseEmitter emitter, List<ToolResult> toolResults) {
        for (ToolResult result : toolResults) {
            if (result.toolCall() == null || !StageOrderDraftTool.TOOL_STAGE_ORDER_DRAFT.equals(result.toolCall().name())) {
                continue;
            }
            if (result.isSuccess() && result.data() != null) {
                sendEvent(emitter, "draft", result.data());
            } else if (result.isError() && (result.actions() != null || result.data() != null)) {
                Map<String, Object> problem = new LinkedHashMap<>();
                if (result.data() != null) {
                    problem.putAll(result.data());
                }
                problem.put("actions", result.actions());
                sendEvent(emitter, "problem", problem);
            }
        }
    }

    private String currentSessionStatus(String sessionId) {
        if (sessionRepository == null) {
            return AssistantSessionStatus.ACTIVE.name();
        }
        return sessionRepository.findById(sessionId)
                .map(session -> session.getStatus().name())
                .orElse(AssistantSessionStatus.ACTIVE.name());
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.warn("Failed to emit SSE '{}' event for a streaming turn: {}", eventName, ex.getMessage());
        }
    }

    private ConversationLoopResult executeConversationLoop(
            String sessionId,
            String userId,
            List<AssistantMessage> history,
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
                    resolvedIntent.acceptedTools().size()
            );

            ModelResponse modelResponse = queryModel(history, resolvedIntent.acceptedTools(), context);

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
                resolvedIntent
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

        return new ToolExecutionOutcome(turns, policyDenied, denialMessage, toolResults != null ? toolResults : List.of());
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
