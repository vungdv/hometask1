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
import vn.danang.polaris.assistant.mcp.ExternalMcpHub;
import vn.danang.polaris.assistant.model.AssistantModelClient;
import vn.danang.polaris.assistant.model.ModelResponse;
import vn.danang.polaris.assistant.model.ToolCall;

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
    // In-memory conversation store: sessionId -> List of AssistantMessage
    private final Map<String, List<AssistantMessage>> conversationStore = new ConcurrentHashMap<>();

    @Autowired
    public AssistantChatService(
            AssistantModelClient modelClient,
            ExternalMcpHub mcpHub,
            ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracerProvider) {
        this(modelClient, mcpHub, objectMapper, tracerProvider != null ? tracerProvider.getIfAvailable() : null);
    }

    public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub mcpHub, ObjectMapper objectMapper) {
        this(modelClient, mcpHub, objectMapper, (Tracer) null);
    }

    public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub mcpHub) {
        this(modelClient, mcpHub, new ObjectMapper(), (Tracer) null);
    }

    public AssistantChatService(AssistantModelClient modelClient) {
        this(modelClient, null, new ObjectMapper(), (Tracer) null);
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ExternalMcpHub mcpHub,
            ObjectMapper objectMapper,
            @Nullable Tracer tracer) {
        this.modelClient = modelClient;
        this.mcpHub = mcpHub;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.tracer = tracer;
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

        // 4. Autonomous tool execution loop (ReAct loop)
        int iterations = 0;
        String finalReply = null;
        String finalThoughtSignature = null;

        while (iterations < MAX_TOOL_ITERATIONS) {
            iterations++;
            recordEvent(span, "agent.iteration.started");
            log.info("Executing conversation turn iteration {} for sessionId: {}, userId: {}", iterations, sessionId, userId);

            recordEvent(span, "model.request");
            ModelResponse modelResponse = modelClient.generateResponse(new ArrayList<>(history), availableTools);
            if (modelResponse == null) {
                String fallbackText = modelClient.chat(new ArrayList<>(history));
                modelResponse = new ModelResponse(fallbackText != null ? fallbackText : "");
            }
            recordEvent(span, "model.response");

            if (modelResponse.hasToolCalls() && mcpHub != null) {
                List<AssistantMessage> modelTurns = new ArrayList<>();
                List<AssistantMessage> toolTurns = new ArrayList<>();

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

                    // Execute tool via MCP
                    recordEvent(span, "agent.tool.call");
                    CallToolResult toolResult = mcpHub.executeTool(toolCall.name(), toolCall.arguments());
                    String resultText = extractToolResultText(toolResult);
                    recordEvent(span, "agent.tool.result");

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
            } else {
                finalReply = modelResponse.text();
                finalThoughtSignature = modelResponse.thoughtSignature();
                break;
            }
        }

        if (finalReply == null || finalReply.isBlank()) {
            finalReply = "I have completed processing your request.";
        }

        recordEvent(span, "agent.response.generated");
        if (span != null) {
            span.tag("agent.iterations.count", String.valueOf(iterations));
        }

        // 5. Append assistant reply to history
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
}
