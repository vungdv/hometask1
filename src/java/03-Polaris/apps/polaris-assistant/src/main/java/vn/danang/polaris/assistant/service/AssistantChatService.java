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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
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
    // In-memory conversation store: sessionId -> List of AssistantMessage
    private final Map<String, List<AssistantMessage>> conversationStore = new ConcurrentHashMap<>();

    @Autowired
    public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub mcpHub, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.mcpHub = mcpHub;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub mcpHub) {
        this(modelClient, mcpHub, new ObjectMapper());
    }

    public AssistantChatService(AssistantModelClient modelClient) {
        this(modelClient, null, new ObjectMapper());
    }

    public ChatMessageResponse sendMessage(ChatMessageRequest request, String userId) {
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

        // 4. Autonomous tool execution loop (ReAct loop)
        int iterations = 0;
        String finalReply = null;

        while (iterations < MAX_TOOL_ITERATIONS) {
            iterations++;
            log.info("Executing conversation turn iteration {} for sessionId: {}, userId: {}", iterations, sessionId, userId);

            ModelResponse modelResponse = modelClient.generateResponse(new ArrayList<>(history), availableTools);
            if (modelResponse == null) {
                String fallbackText = modelClient.chat(new ArrayList<>(history));
                modelResponse = new ModelResponse(fallbackText != null ? fallbackText : "");
            }

            if (modelResponse.hasToolCalls() && mcpHub != null) {
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
                    modelTurn.setCreatedAt(Instant.now());
                    history.add(modelTurn);

                    // Execute tool via MCP
                    CallToolResult toolResult = mcpHub.executeTool(toolCall.name(), toolCall.arguments());
                    String resultText = extractToolResultText(toolResult);

                    // Save tool execution result turn to message history
                    AssistantMessage toolTurn = new AssistantMessage();
                    toolTurn.setRole(MessageRole.TOOL);
                    toolTurn.setToolCallId(toolCall.name());
                    toolTurn.setContent(resultText);
                    toolTurn.setCreatedAt(Instant.now());
                    history.add(toolTurn);
                }
            } else {
                finalReply = modelResponse.text();
                break;
            }
        }

        if (finalReply == null || finalReply.isBlank()) {
            finalReply = "I have completed processing your request.";
        }

        // 5. Append assistant reply to history
        AssistantMessage assistantMsg = new AssistantMessage();
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent(finalReply);
        assistantMsg.setCreatedAt(Instant.now());
        history.add(assistantMsg);

        return new ChatMessageResponse(
                sessionId,
                MessageRole.ASSISTANT.name(),
                finalReply,
                assistantMsg.getCreatedAt()
        );
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
