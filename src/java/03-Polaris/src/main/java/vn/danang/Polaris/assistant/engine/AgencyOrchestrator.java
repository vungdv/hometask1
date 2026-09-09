package vn.danang.polaris.assistant.engine;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.model.AssistantModelClient;
import vn.danang.polaris.assistant.model.ModelEvent;
import vn.danang.polaris.assistant.model.ModelStreamListener;
import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.tool.AssistantToolRegistry;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Component
public class AgencyOrchestrator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AssistantModelClient modelClient;
    private final AssistantToolRegistry toolRegistry;
    private final AssistantSessionRepository sessionRepository;
    private final AssistantMessageRepository messageRepository;
    private final ExecutorService assistantExecutor;

    public AgencyOrchestrator(
            AssistantModelClient modelClient,
            AssistantToolRegistry toolRegistry,
            AssistantSessionRepository sessionRepository,
            AssistantMessageRepository messageRepository,
            @Qualifier("assistantExecutor") ExecutorService assistantExecutor) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.assistantExecutor = assistantExecutor;
    }

    @Transactional
    public void processUserMessage(String sessionId, String userPrompt, ModelStreamListener listener) {
        AssistantSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Assistant session not found with id: " + sessionId));

        if (session.getStatus() == SessionStatus.CLOSED) {
            String errorMsg = "Cannot send message to a CLOSED session (" + sessionId + ").";
            listener.onError(errorMsg, "Create a new assistant session via POST /api/v1/assistant/sessions");
            listener.onDone("ERROR");
            return;
        }

        // 1. Persist User Prompt Message Turn
        AssistantMessage userMessage = new AssistantMessage();
        userMessage.setSession(session);
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(userPrompt);
        userMessage.setCreatedAt(Instant.now());
        messageRepository.save(userMessage);

        // 2. Build Session Context
        List<AssistantMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        SessionContext context = new SessionContext(
                sessionId,
                session.getUserId(),
                session.getCustomerId(),
                history,
                userPrompt
        );

        // 3. Accumulators for Assistant Turn
        StringBuilder tokenAccumulator = new StringBuilder();
        AtomicReference<String> widgetTypeRef = new AtomicReference<>();
        AtomicReference<String> widgetPayloadRef = new AtomicReference<>();
        AtomicReference<String> toolCallIdRef = new AtomicReference<>();

        ModelStreamListener interceptingListener = event -> {
            switch (event) {
                case ModelEvent.TokenDeltaEvent(String delta) -> {
                    if (delta != null) tokenAccumulator.append(delta);
                }
                case ModelEvent.WidgetEvent(String widgetType, Object payload) -> {
                    widgetTypeRef.set(widgetType);
                    widgetPayloadRef.set(serializeToJson(payload));
                }
                case ModelEvent.DraftEvent(var draft) -> {
                    widgetTypeRef.set("DRAFT_CARD");
                    widgetPayloadRef.set(serializeToJson(draft));
                }
                case ModelEvent.ToolCallRequestEvent(String toolCallId, String toolName, var args) -> {
                    toolCallIdRef.set(toolCallId);
                }
                default -> {}
            }
            listener.onEvent(event);
        };

        // 4. Invoke Pluggable Cognitive Model
        try {
            modelClient.streamChat(context, toolRegistry.getAllTools(), interceptingListener);
        } catch (Exception e) {
            listener.onError("Inference error: " + e.getMessage(), "Try again or contact support.");
            listener.onDone("ERROR");
        } finally {
            // 5. Persist Assistant Message Turn
            if (!tokenAccumulator.isEmpty() || widgetTypeRef.get() != null) {
                AssistantMessage assistantMessage = new AssistantMessage();
                assistantMessage.setSession(session);
                assistantMessage.setRole(MessageRole.ASSISTANT);
                assistantMessage.setContent(tokenAccumulator.toString());
                assistantMessage.setWidgetType(widgetTypeRef.get());
                assistantMessage.setWidgetPayload(widgetPayloadRef.get());
                assistantMessage.setToolCallId(toolCallIdRef.get());
                assistantMessage.setCreatedAt(Instant.now());
                messageRepository.save(assistantMessage);

                session.setUpdatedAt(Instant.now());
                sessionRepository.save(session);
            }
        }
    }

    public CompletableFuture<Void> processUserMessageAsync(String sessionId, String userPrompt, ModelStreamListener listener) {
        return CompletableFuture.runAsync(
                () -> processUserMessage(sessionId, userPrompt, listener),
                assistantExecutor
        );
    }

    private String serializeToJson(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
