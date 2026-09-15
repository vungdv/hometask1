package vn.danang.polaris.assistant.service;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

class AssistantChatServiceTest {

    private AssistantModelClient modelClient;
    private AssistantChatService chatService;

    @BeforeEach
    void setUp() {
        modelClient = mock(AssistantModelClient.class);
        chatService = new AssistantChatService(modelClient);
    }

    @Test
    @DisplayName("Should forward user message to AssistantModelClient and return response")
    @SuppressWarnings("unchecked")
    void sendMessage_withValidMessage_forwardsToModelClientAndReturnsResponse() {
        ChatMessageRequest request = new ChatMessageRequest("Tell me about Polaris");
        when(modelClient.chat(anyList())).thenReturn("Polaris is an enterprise ecommerce platform.");

        ChatMessageResponse response = chatService.sendMessage(request, "user-123");

        assertThat(response).isNotNull();
        assertThat(response.role()).isEqualTo("ASSISTANT");
        assertThat(response.reply()).isEqualTo("Polaris is an enterprise ecommerce platform.");
        assertThat(response.createdAt()).isNotNull();

        ArgumentCaptor<List<AssistantMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelClient).chat(captor.capture());

        List<AssistantMessage> sentMessages = captor.getValue();
        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(sentMessages.get(0).getContent()).isEqualTo("Tell me about Polaris");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is blank")
    void sendMessage_withBlankMessage_throwsIllegalArgumentException() {
        ChatMessageRequest request = new ChatMessageRequest("   ");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> chatService.sendMessage(request, "user-123"));

        assertThat(exception).hasMessage("Message content must not be blank.");
        verify(modelClient, never()).chat(anyList());
    }

    @Test
    @DisplayName("Should execute MCP tool in ReAct loop and return model's final reply")
    void sendMessage_withToolCall_executesToolAndLoopsToFinalReply() {
        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        chatService = new AssistantChatService(modelClient, mcpHub);

        Tool tool = Tool.builder("search_available_products")
                .description("Search catalog products")
                .build();
        when(mcpHub.discoverAllTools()).thenReturn(List.of(tool));

        CallToolResult toolResult = new CallToolResult(
                List.of(new TextContent("Found: Fast Charger 65W ($24.90)")),
                false,
                null,
                Map.of()
        );
        when(mcpHub.executeTool(eq("search_available_products"), any())).thenReturn(toolResult);

        // Turn 1: Model requests tool call
        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("search_available_products", Map.of("query", "charger"))
        ));
        // Turn 2: Model returns final text reply
        ModelResponse turn2Response = new ModelResponse("I found the Fast Charger 65W for $24.90.", List.of());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        ChatMessageRequest request = new ChatMessageRequest("Find fast chargers");
        ChatMessageResponse response = chatService.sendMessage(request, "user-123");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("I found the Fast Charger 65W for $24.90.");
        verify(mcpHub, times(1)).executeTool(eq("search_available_products"), eq(Map.of("query", "charger")));
        verify(modelClient, times(2)).generateResponse(anyList(), anyList());
    }

    @Test
    @DisplayName("Should preserve conversation history across multiple turns with same sessionId")
    void sendMessage_acrossMultipleTurns_preservesConversationHistory() {
        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(new ModelResponse("Hello! How can I help you?", List.of()))
                .thenReturn(new ModelResponse("Da Nang is a coastal city in Vietnam.", List.of()));

        String sessionId = "session-persist-100";
        ChatMessageRequest msg1 = new ChatMessageRequest(sessionId, "Hi");
        ChatMessageResponse resp1 = chatService.sendMessage(msg1, "user-123");
        assertThat(resp1.reply()).isEqualTo("Hello! How can I help you?");

        ChatMessageRequest msg2 = new ChatMessageRequest(sessionId, "Tell me about Da Nang");
        ChatMessageResponse resp2 = chatService.sendMessage(msg2, "user-123");
        assertThat(resp2.reply()).isEqualTo("Da Nang is a coastal city in Vietnam.");

        ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList());

        List<AssistantMessage> secondTurnHistory = historyCaptor.getAllValues().get(1);
        // History should contain: Turn 1 User, Turn 1 Assistant, Turn 2 User
        assertThat(secondTurnHistory).hasSize(3);
        assertThat(secondTurnHistory.get(0).getContent()).isEqualTo("Hi");
        assertThat(secondTurnHistory.get(1).getContent()).isEqualTo("Hello! How can I help you?");
        assertThat(secondTurnHistory.get(2).getContent()).isEqualTo("Tell me about Da Nang");
    }

    @Test
    @DisplayName("Should terminate safely when tool loop exceeds maximum iterations")
    void sendMessage_whenToolCallsExceedMaxIterations_terminatesSafely() {
        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        chatService = new AssistantChatService(modelClient, mcpHub);

        when(mcpHub.discoverAllTools()).thenReturn(List.of(Tool.builder("looping_tool").build()));
        when(mcpHub.executeTool(any(), any())).thenReturn(new CallToolResult(List.of(new TextContent("ok")), false, null, Map.of()));

        // Model perpetually returns a tool call
        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(new ModelResponse("", List.of(new ToolCall("looping_tool", Map.of()))));

        ChatMessageRequest request = new ChatMessageRequest("Run loop");
        ChatMessageResponse response = chatService.sendMessage(request, "user-123");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("I have completed processing your request.");
        // Max iterations is 5
        verify(modelClient, times(5)).generateResponse(anyList(), anyList());
        verify(mcpHub, times(5)).executeTool(any(), any());
    }
}

