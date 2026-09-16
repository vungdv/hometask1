package vn.danang.polaris.assistant.service;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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

    private Tracer mockTracerSetup(Span mockSpan, Tracer.SpanInScope mockSpanInScope) {
        Tracer tracer = mock(Tracer.class);
        when(tracer.nextSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockSpan.event(anyString())).thenReturn(mockSpan);
        when(tracer.withSpan(mockSpan)).thenReturn(mockSpanInScope);
        return tracer;
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

        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("default_api:search_available_products", Map.of("query", "charger"), "sig_token_xyz789")
        ));
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

    // =========================================================================
    // WO-013 & PRD-005: Turn Enclosing Span and ReAct Lifecycle Events Tests
    // =========================================================================

    @Test
    @DisplayName("WO-013: Should emit full lifecycle span events in exact order with tool calls")
    void sendMessage_withToolCall_emitsFullLifecycleSpanEventsInOrder() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        Tool tool = Tool.builder("search_available_products").build();
        when(mcpHub.discoverAllTools()).thenReturn(List.of(tool));

        CallToolResult toolResult = new CallToolResult(
                List.of(new TextContent("Found: Fast Charger 65W ($24.90)")),
                false,
                null,
                Map.of()
        );
        when(mcpHub.executeTool(eq("search_available_products"), any())).thenReturn(toolResult);

        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("search_available_products", Map.of("query", "charger"))
        ));
        ModelResponse turn2Response = new ModelResponse("I found the Fast Charger 65W for $24.90.", List.of());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        AssistantChatService serviceWithTracer = new AssistantChatService(
                modelClient, mcpHub, new ObjectMapper(), tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-001", "Find fast chargers");
        ChatMessageResponse response = serviceWithTracer.sendMessage(request, "user-456");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("I found the Fast Charger 65W for $24.90.");

        // Verify span initialization & attributes
        verify(tracer).nextSpan();
        verify(span).name("agent.turn");
        verify(span).tag("agent.name", "assistant-chat");
        verify(span).tag("agent.framework", "polaris-assistant");
        verify(span).tag("agent.session_id", "sess-001");
        verify(span).tag("agent.user_id", "user-456");
        verify(span).tag("agent.tools.count", "1");
        verify(span).tag("agent.iterations.count", "2");
        verify(span).start();
        verify(tracer).withSpan(span);

        // Verify exact 12-step event sequence in order
        InOrder inOrder = inOrder(span);
        inOrder.verify(span).event("agent.request.received");
        inOrder.verify(span).event("tools.discovered");
        inOrder.verify(span).event("agent.iteration.started");
        inOrder.verify(span).event("model.request");
        inOrder.verify(span).event("model.response");
        inOrder.verify(span).event("agent.tool.call");
        inOrder.verify(span).event("agent.tool.result");
        inOrder.verify(span).event("agent.iteration.started");
        inOrder.verify(span).event("model.request");
        inOrder.verify(span).event("model.response");
        inOrder.verify(span).event("agent.response.generated");
        inOrder.verify(span).event("agent.completed");

        verify(span, times(1)).end();
    }

    @Test
    @DisplayName("WO-013: Should emit direct lifecycle events without tool call events when model replies directly")
    void sendMessage_withoutToolCalls_emitsDirectLifecycleEvents() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        when(mcpHub.discoverAllTools()).thenReturn(List.of());

        ModelResponse directResponse = new ModelResponse("Hello! How can I assist you today?", List.of());
        when(modelClient.generateResponse(anyList(), anyList())).thenReturn(directResponse);

        AssistantChatService serviceWithTracer = new AssistantChatService(
                modelClient, mcpHub, new ObjectMapper(), tracer);

        ChatMessageRequest request = new ChatMessageRequest("Hello");
        ChatMessageResponse response = serviceWithTracer.sendMessage(request, null);

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Hello! How can I assist you today?");

        // Anonymous user attribution check
        verify(span).tag("agent.user_id", "anonymous");
        verify(span).tag("agent.tools.count", "0");
        verify(span).tag("agent.iterations.count", "1");

        // Verify direct lifecycle events in order
        InOrder inOrder = inOrder(span);
        inOrder.verify(span).event("agent.request.received");
        inOrder.verify(span).event("tools.discovered");
        inOrder.verify(span).event("agent.iteration.started");
        inOrder.verify(span).event("model.request");
        inOrder.verify(span).event("model.response");
        inOrder.verify(span).event("agent.response.generated");
        inOrder.verify(span).event("agent.completed");

        // Tool events must NEVER be emitted
        verify(span, never()).event("agent.tool.call");
        verify(span, never()).event("agent.tool.result");

        verify(span, times(1)).end();
    }

    @Test
    @DisplayName("WO-013: Should tag error and record exception on span when runtime exception occurs")
    void sendMessage_whenExceptionOccursInModel_tagsErrorAndRecordsExceptionOnSpan() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenThrow(new RuntimeException("Gemini API connection failure"));

        AssistantChatService serviceWithTracer = new AssistantChatService(
                modelClient, null, new ObjectMapper(), tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-err", "Trigger model error");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> serviceWithTracer.sendMessage(request, "user-err"));

        assertThat(thrown).hasMessage("Gemini API connection failure");

        verify(span).event("agent.request.received");
        verify(span).event("tools.discovered");
        verify(span).event("agent.iteration.started");
        verify(span).event("model.request");
        verify(span).error(thrown);
        verify(span).tag("error", "true");
        verify(span, times(1)).end();
    }

    @Test
    @DisplayName("WO-013: Should record request received, tag error, and end span on blank message validation failure")
    void sendMessage_whenBlankMessageWithTracer_recordsEventAndErrorOnSpan() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        AssistantChatService serviceWithTracer = new AssistantChatService(
                modelClient, null, new ObjectMapper(), tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-blank", "   ");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> serviceWithTracer.sendMessage(request, "user-blank"));

        assertThat(thrown).hasMessage("Message content must not be blank.");

        verify(span).event("agent.request.received");
        verify(span).error(thrown);
        verify(span).tag("error", "true");
        verify(span, times(1)).end();
    }

    @Test
    @DisplayName("WO-013: Should emit paired tool.call and tool.result for each tool call in parallel invocation")
    void sendMessage_withParallelToolCalls_emitsEventPairForEachTool() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        when(mcpHub.discoverAllTools()).thenReturn(List.of(
                Tool.builder("search_products").build(),
                Tool.builder("search_promotions").build()
        ));
        when(mcpHub.executeTool(eq("search_products"), any())).thenReturn(new CallToolResult(List.of(new TextContent("Charger")), false, null, Map.of()));
        when(mcpHub.executeTool(eq("search_promotions"), any())).thenReturn(new CallToolResult(List.of(new TextContent("10% off")), false, null, Map.of()));

        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("search_products", Map.of("query", "charger")),
                new ToolCall("search_promotions", Map.of("category", "deals"))
        ));
        ModelResponse turn2Response = new ModelResponse("Found charger with 10% discount.", List.of());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        AssistantChatService serviceWithTracer = new AssistantChatService(
                modelClient, mcpHub, new ObjectMapper(), tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-parallel", "Find charger deals");
        ChatMessageResponse response = serviceWithTracer.sendMessage(request, "user-999");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Found charger with 10% discount.");

        // Verify paired tool events emitted twice
        InOrder inOrder = inOrder(span);
        inOrder.verify(span).event("agent.iteration.started");
        inOrder.verify(span).event("model.request");
        inOrder.verify(span).event("model.response");
        inOrder.verify(span).event("agent.tool.call");
        inOrder.verify(span).event("agent.tool.result");
        inOrder.verify(span).event("agent.tool.call");
        inOrder.verify(span).event("agent.tool.result");
        inOrder.verify(span).event("agent.iteration.started");
        inOrder.verify(span).event("model.request");
        inOrder.verify(span).event("model.response");
        inOrder.verify(span).event("agent.response.generated");
        inOrder.verify(span).event("agent.completed");

        verify(span).tag("agent.tools.count", "2");
        verify(span).tag("agent.iterations.count", "2");
        verify(span).end();
    }

    @Test
    @DisplayName("WO-013: Should emit 5 iteration events and tag iterations.count=5 when reaching maximum iterations")
    void sendMessage_whenExceedingMaxIterationsWithTracer_recordsAllIterationsAndTagsCount() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        when(mcpHub.discoverAllTools()).thenReturn(List.of(Tool.builder("loop_tool").build()));
        when(mcpHub.executeTool(any(), any())).thenReturn(new CallToolResult(List.of(new TextContent("ok")), false, null, Map.of()));

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(new ModelResponse("", List.of(new ToolCall("loop_tool", Map.of()))));

        AssistantChatService serviceWithTracer = new AssistantChatService(
                modelClient, mcpHub, new ObjectMapper(), tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-max", "Perpetual loop");
        ChatMessageResponse response = serviceWithTracer.sendMessage(request, "user-loop");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("I have completed processing your request.");

        verify(span, times(5)).event("agent.iteration.started");
        verify(span, times(5)).event("agent.tool.call");
        verify(span, times(5)).event("agent.tool.result");
        verify(span).event("agent.response.generated");
        verify(span).event("agent.completed");
        verify(span).tag("agent.iterations.count", "5");
        verify(span).end();
    }

    @Test
    @DisplayName("WO-013: Should extract Tracer bean from ObjectProvider in @Autowired constructor")
    @SuppressWarnings("unchecked")
    void constructor_withObjectProvider_extractsTracerSuccessfully() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);

        AssistantChatService service = new AssistantChatService(
                modelClient, null, new ObjectMapper(), provider);

        when(modelClient.chat(anyList())).thenReturn("Reply");
        ChatMessageResponse response = service.sendMessage(new ChatMessageRequest("Hi"), "user-1");

        assertThat(response).isNotNull();
        verify(tracer).nextSpan();
        verify(span).name("agent.turn");
    }

    @Test
    @DisplayName("WO-013: Should handle null ObjectProvider cleanly without tracer")
    void constructor_withNullObjectProvider_handlesCleanly() {
        AssistantChatService service = new AssistantChatService(
                modelClient, null, new ObjectMapper(), (ObjectProvider<Tracer>) null);

        when(modelClient.chat(anyList())).thenReturn("Reply");
        ChatMessageResponse response = service.sendMessage(new ChatMessageRequest("Hi"), "user-1");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Reply");
    }
}
