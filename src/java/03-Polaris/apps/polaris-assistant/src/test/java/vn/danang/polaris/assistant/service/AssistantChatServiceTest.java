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

    @Test
    @DisplayName("Should preserve thoughtSignature on AssistantMessage in history across tool execution turns")
    @SuppressWarnings("unchecked")
    void sendMessage_withToolCallAndThoughtSignature_propagatesSignatureToNextTurnHistory() {
        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        chatService = new AssistantChatService(modelClient, mcpHub);

        Tool tool = Tool.builder("default_api:search_available_products").build();
        when(mcpHub.discoverAllTools()).thenReturn(List.of(tool));
        when(mcpHub.executeTool(any(), any())).thenReturn(new CallToolResult(List.of(new TextContent("Product found: Charger")), false, null, Map.of()));

        // Turn 1: Model requests tool call with a thought signature
        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("default_api:search_available_products", Map.of("query", "charger"), "sig_token_xyz789")
        ));
        // Turn 2: Model returns final response with its own thought signature
        ModelResponse turn2Response = new ModelResponse("I found the charger for you.", List.of(), "sig_final_reply_token");

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        ChatMessageRequest request = new ChatMessageRequest("Search for charger");
        ChatMessageResponse response = chatService.sendMessage(request, "user-123");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("I found the charger for you.");

        ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList());

        // Inspect history passed to Turn 2:
        // [0]: USER ("Search for charger")
        // [1]: ASSISTANT (toolCallId: default_api:search_available_products, thoughtSignature: "sig_token_xyz789")
        // [2]: TOOL (result)
        List<AssistantMessage> turn2History = historyCaptor.getAllValues().get(1);
        assertThat(turn2History).hasSize(3);

        AssistantMessage modelTurn = turn2History.get(1);
        assertThat(modelTurn.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(modelTurn.getToolCallId()).isEqualTo("default_api:search_available_products");
        assertThat(modelTurn.getThoughtSignature()).isEqualTo("sig_token_xyz789");
    }

    @Test
    @DisplayName("Should group parallel model turns before tool turns to prevent interleaved turns in history")
    @SuppressWarnings("unchecked")
    void sendMessage_withParallelToolCalls_groupsModelTurnsBeforeToolTurnsInHistory() {
        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        chatService = new AssistantChatService(modelClient, mcpHub);

        when(mcpHub.discoverAllTools()).thenReturn(List.of(
                Tool.builder("search_products").build(),
                Tool.builder("search_promotions").build()
        ));
        when(mcpHub.executeTool(eq("search_products"), any())).thenReturn(new CallToolResult(List.of(new TextContent("Charger")), false, null, Map.of()));
        when(mcpHub.executeTool(eq("search_promotions"), any())).thenReturn(new CallToolResult(List.of(new TextContent("10% off")), false, null, Map.of()));

        // Model returns 2 parallel tool calls
        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("search_products", Map.of("query", "charger"), "sig_parallel_call"),
                new ToolCall("search_promotions", Map.of("category", "all"), null)
        ));
        ModelResponse turn2Response = new ModelResponse("Found charger with 10% discount.", List.of());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        ChatMessageRequest request = new ChatMessageRequest("Search charger and deals");
        ChatMessageResponse response = chatService.sendMessage(request, "user-123");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Found charger with 10% discount.");

        ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList());

        List<AssistantMessage> turn2History = historyCaptor.getAllValues().get(1);
        // History order must be:
        // [0]: USER
        // [1]: ASSISTANT (search_products)
        // [2]: ASSISTANT (search_promotions)
        // [3]: TOOL (search_products)
        // [4]: TOOL (search_promotions)
        assertThat(turn2History).hasSize(5);
        assertThat(turn2History.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(turn2History.get(1).getToolCallId()).isEqualTo("search_products");
        assertThat(turn2History.get(1).getThoughtSignature()).isEqualTo("sig_parallel_call");

        assertThat(turn2History.get(2).getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(turn2History.get(2).getToolCallId()).isEqualTo("search_promotions");

        assertThat(turn2History.get(3).getRole()).isEqualTo(MessageRole.TOOL);
        assertThat(turn2History.get(3).getToolCallId()).isEqualTo("search_products");

        assertThat(turn2History.get(4).getRole()).isEqualTo(MessageRole.TOOL);
        assertThat(turn2History.get(4).getToolCallId()).isEqualTo("search_promotions");
    }
}

