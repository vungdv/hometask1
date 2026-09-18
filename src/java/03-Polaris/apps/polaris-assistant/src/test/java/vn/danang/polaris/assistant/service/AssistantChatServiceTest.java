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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentResolver;
import vn.danang.polaris.assistant.intent.IntentToolRegistry;
import vn.danang.polaris.assistant.intent.PolicyDecision;
import vn.danang.polaris.assistant.intent.PolicyEngine;
import vn.danang.polaris.assistant.mcp.ExternalMcpHub;
import vn.danang.polaris.assistant.mcp.McpHub;
import vn.danang.polaris.assistant.mcp.PolarisMcpClient;
import vn.danang.polaris.assistant.mcp.ToolExecutionContext;
import vn.danang.polaris.assistant.mcp.ToolExecutionResult;
import vn.danang.polaris.assistant.model.AssistantModelClient;
import vn.danang.polaris.assistant.model.ModelRequestContext;
import vn.danang.polaris.assistant.model.ModelResponse;
import vn.danang.polaris.assistant.model.ToolCall;
import vn.danang.polaris.assistant.observability.AgentDecisionRecorder;

class AssistantChatServiceTest {

    private AssistantModelClient modelClient;
    private McpHub mcpHub;
    private AssistantChatService chatService;

    @BeforeEach
    void setUp() {
        modelClient = mock(AssistantModelClient.class);
        mcpHub = mock(McpHub.class);
        when(mcpHub.discoverAllTools()).thenReturn(List.of());
        chatService = createChatService(modelClient, mcpHub);
    }

    private ExternalMcpHub createMcpHub() {
        return createMcpHub((Tracer) null);
    }

    private ExternalMcpHub createMcpHub(Tracer tracer) {
        PolarisMcpClient client = mock(PolarisMcpClient.class);
        return spy(new ExternalMcpHub(client, tracer));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        if (instance == null) {
            return null;
        }
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private AssistantChatService createChatService(AssistantModelClient modelClient) {
        McpHub hub = mock(McpHub.class);
        when(hub.discoverAllTools()).thenReturn(List.of());
        return createChatService(modelClient, hub, null);
    }

    private AssistantChatService createChatService(AssistantModelClient modelClient, McpHub mcpHub) {
        return createChatService(modelClient, mcpHub, null);
    }

    private AssistantChatService createChatService(AssistantModelClient modelClient, McpHub mcpHub, Tracer tracer) {
        return createChatService(modelClient, mcpHub, new ObjectMapper(), tracer, null, null, null, null);
    }

    private AssistantChatService createChatService(
            AssistantModelClient modelClient,
            McpHub mcpHub,
            ObjectMapper objectMapper,
            Tracer tracer,
            AgentDecisionRecorder decisionRecorder,
            IntentResolver intentResolver,
            IntentToolRegistry intentToolRegistry,
            PolicyEngine policyEngine) {
        return new AssistantChatService(
                modelClient,
                mcpHub,
                objectMapper != null ? objectMapper : new ObjectMapper(),
                providerOf(tracer),
                providerOf(decisionRecorder),
                providerOf(intentResolver),
                providerOf(intentToolRegistry),
                providerOf(policyEngine)
        );
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
        ExternalMcpHub mcpHub = createMcpHub();
        chatService = createChatService(modelClient, mcpHub);

        Tool tool = Tool.builder("search_available_products")
                .description("Search catalog products")
                .build();
        doReturn(List.of(tool)).when(mcpHub).discoverAllTools();

        CallToolResult toolResult = new CallToolResult(
                List.of(new TextContent("Found: Fast Charger 65W ($24.90)")),
                false,
                null,
                Map.of()
        );
        doReturn(toolResult).when(mcpHub).executeTool(eq("search_available_products"), any());

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
        ExternalMcpHub mcpHub = createMcpHub();
        chatService = createChatService(modelClient, mcpHub);

        doReturn(List.of(Tool.builder("looping_tool").build())).when(mcpHub).discoverAllTools();
        doReturn(new CallToolResult(List.of(new TextContent("ok")), false, null, Map.of())).when(mcpHub).executeTool(any(), any());

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
        ExternalMcpHub mcpHub = createMcpHub();
        chatService = createChatService(modelClient, mcpHub);

        Tool tool = Tool.builder("default_api:search_available_products").build();
        doReturn(List.of(tool)).when(mcpHub).discoverAllTools();
        doReturn(new CallToolResult(List.of(new TextContent("Product found: Charger")), false, null, Map.of())).when(mcpHub).executeTool(any(), any());

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
        ExternalMcpHub mcpHub = createMcpHub();
        chatService = createChatService(modelClient, mcpHub);

        doReturn(List.of(
                Tool.builder("search_products").build(),
                Tool.builder("search_promotions").build()
        )).when(mcpHub).discoverAllTools();
        doReturn(new CallToolResult(List.of(new TextContent("Charger")), false, null, Map.of())).when(mcpHub).executeTool(eq("search_products"), any());
        doReturn(new CallToolResult(List.of(new TextContent("10% off")), false, null, Map.of())).when(mcpHub).executeTool(eq("search_promotions"), any());

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

        ExternalMcpHub mcpHub = createMcpHub(tracer);
        Tool tool = Tool.builder("search_available_products").build();
        doReturn(List.of(tool)).when(mcpHub).discoverAllTools();

        CallToolResult toolResult = new CallToolResult(
                List.of(new TextContent("Found: Fast Charger 65W ($24.90)")),
                false,
                null,
                Map.of()
        );
        doReturn(toolResult).when(mcpHub).executeTool(eq("search_available_products"), any());

        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("search_available_products", Map.of("query", "charger"))
        ));
        ModelResponse turn2Response = new ModelResponse("I found the Fast Charger 65W for $24.90.", List.of());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        AssistantChatService serviceWithTracer = createChatService(
                modelClient, mcpHub, tracer);

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
        inOrder.verify(span).event("agent.tool.call: search_available_products");
        inOrder.verify(span).event("agent.tool.result: search_available_products");
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

        AssistantChatService serviceWithTracer = createChatService(
                modelClient, mcpHub, tracer);

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

        McpHub mockMcpHub = mock(McpHub.class);
        when(mockMcpHub.discoverAllTools()).thenReturn(List.of());

        AssistantChatService serviceWithTracer = createChatService(
                modelClient, mockMcpHub, tracer);

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

        McpHub mockMcpHub = mock(McpHub.class);
        when(mockMcpHub.discoverAllTools()).thenReturn(List.of());

        AssistantChatService serviceWithTracer = createChatService(
                modelClient, mockMcpHub, tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-blank", "   ");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> serviceWithTracer.sendMessage(request, "user-blank"));

        assertThat(thrown).hasMessage("Message content must not be blank.");

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

        ExternalMcpHub mcpHub = createMcpHub(tracer);
        doReturn(List.of(
                Tool.builder("search_products").build(),
                Tool.builder("search_promotions").build()
        )).when(mcpHub).discoverAllTools();
        doReturn(new CallToolResult(List.of(new TextContent("Charger")), false, null, Map.of())).when(mcpHub).executeTool(eq("search_products"), any());
        doReturn(new CallToolResult(List.of(new TextContent("10% off")), false, null, Map.of())).when(mcpHub).executeTool(eq("search_promotions"), any());

        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("search_products", Map.of("query", "charger")),
                new ToolCall("search_promotions", Map.of("category", "deals"))
        ));
        ModelResponse turn2Response = new ModelResponse("Found charger with 10% discount.", List.of());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        AssistantChatService serviceWithTracer = createChatService(
                modelClient, mcpHub, tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-parallel", "Find charger deals");
        ChatMessageResponse response = serviceWithTracer.sendMessage(request, "user-999");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Found charger with 10% discount.");

        // Verify paired tool events emitted twice
        InOrder inOrder = inOrder(span);
        inOrder.verify(span).event("agent.iteration.started");
        inOrder.verify(span).event("model.request");
        inOrder.verify(span).event("model.response");
        inOrder.verify(span).event("agent.tool.call: search_products");
        inOrder.verify(span).event("agent.tool.result: search_products");
        inOrder.verify(span).event("agent.tool.call: search_promotions");
        inOrder.verify(span).event("agent.tool.result: search_promotions");
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

        ExternalMcpHub mcpHub = createMcpHub(tracer);
        doReturn(List.of(Tool.builder("loop_tool").build())).when(mcpHub).discoverAllTools();
        doReturn(new CallToolResult(List.of(new TextContent("ok")), false, null, Map.of())).when(mcpHub).executeTool(any(), any());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(new ModelResponse("", List.of(new ToolCall("loop_tool", Map.of()))));

        AssistantChatService serviceWithTracer = createChatService(
                modelClient, mcpHub, tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-max", "Perpetual loop");
        ChatMessageResponse response = serviceWithTracer.sendMessage(request, "user-loop");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("I have completed processing your request.");

        verify(span, times(5)).event("agent.iteration.started");
        verify(span, times(5)).event("agent.tool.call: loop_tool");
        verify(span, times(5)).event("agent.tool.result: loop_tool");
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

        McpHub mockMcpHub = mock(McpHub.class);
        when(mockMcpHub.discoverAllTools()).thenReturn(List.of());

        AssistantChatService service = new AssistantChatService(
                modelClient, mockMcpHub, new ObjectMapper(), provider, null, null, null, null);

        when(modelClient.chat(anyList())).thenReturn("Reply");
        ChatMessageResponse response = service.sendMessage(new ChatMessageRequest("Hi"), "user-1");

        assertThat(response).isNotNull();
        verify(tracer).nextSpan();
        verify(span).name("agent.turn");
    }

    @Test
    @DisplayName("WO-013: Should handle null ObjectProvider cleanly without tracer")
    void constructor_withNullObjectProvider_handlesCleanly() {
        McpHub mockMcpHub = mock(McpHub.class);
        when(mockMcpHub.discoverAllTools()).thenReturn(List.of());

        AssistantChatService service = new AssistantChatService(
                modelClient, mockMcpHub, new ObjectMapper(), null, null, null, null, null);

        when(modelClient.chat(anyList())).thenReturn("Reply");
        ChatMessageResponse response = service.sendMessage(new ChatMessageRequest("Hi"), "user-1");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Reply");
    }

    // =========================================================================
    // WO-014 & ADR-0014: Agent Decision Events Schema and Recording Tests
    // =========================================================================

    @Test
    @DisplayName("WO-014: sendMessage records tool execution decision tags on agent.turn span")
    void sendMessage_withToolCall_recordsDecisionTagsOnSpan() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ExternalMcpHub mcpHub = createMcpHub(tracer);
        Tool tool = Tool.builder("search_available_products").description("Search catalog").build();
        doReturn(List.of(tool)).when(mcpHub).discoverAllTools();

        CallToolResult toolResult = new CallToolResult(
                List.of(new TextContent("Found: Fast Charger 65W ($24.90)")),
                false,
                null,
                Map.of()
        );
        doReturn(toolResult).when(mcpHub).executeTool(eq("search_available_products"), any());

        ModelResponse turn1Response = new ModelResponse("", List.of(
                new ToolCall("search_available_products", Map.of("query", "charger"))
        ));
        ModelResponse turn2Response = new ModelResponse("I found the Fast Charger 65W for $24.90.", List.of());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        AssistantChatService serviceWithTracer = createChatService(
                modelClient, mcpHub, tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-001", "Find fast chargers");
        ChatMessageResponse response = serviceWithTracer.sendMessage(request, "user-456");

        assertThat(response).isNotNull();

        // Verify decision tags attached to span across iterations
        verify(span).tag("decision.action", "search_available_products");
        verify(span).tag("decision.action", "reply_to_user");
        verify(span, atLeastOnce()).tag("decision.intent", "catalog.product.search");
        verify(span, atLeastOnce()).tag("decision.policy", "MAX_TOOL_ITERATIONS=5");
        verify(span, atLeastOnce()).tag("decision.outcome.status", "SUCCESS");
    }

    @Test
    @DisplayName("WO-014: sendMessage records direct response decision tags on agent.turn span")
    void sendMessage_withoutToolCalls_recordsDirectResponseDecisionTagsOnSpan() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        when(mcpHub.discoverAllTools()).thenReturn(List.of());

        ModelResponse directResponse = new ModelResponse("Hello! How can I assist you today?", List.of());
        when(modelClient.generateResponse(anyList(), anyList())).thenReturn(directResponse);

        AssistantChatService serviceWithTracer = createChatService(
                modelClient, mcpHub, tracer);

        ChatMessageRequest request = new ChatMessageRequest("Hello");
        ChatMessageResponse response = serviceWithTracer.sendMessage(request, null);

        assertThat(response).isNotNull();

        // Verify direct response decision tags attached to span
        verify(span).tag("decision.action", "reply_to_user");
        verify(span, atLeastOnce()).tag("decision.intent", "general.conversation");
        verify(span).tag("decision.outcome.status", "SUCCESS");
        verify(span).tag("decision.outcome.detail", "Direct conversational response generated");
    }

    // =========================================================================
    // WO-015 & ADR-0015: Intent Management and Policy Engine Tests
    // =========================================================================

    @Test
    @DisplayName("WO-015: Should narrow tools offered to model client based on resolved intent")
    @SuppressWarnings("unchecked")
    void sendMessage_withIntentManagement_narrowsToolsOfferedToModelClient() {
        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        List<Tool> allTools = List.of(
                Tool.builder("search_available_products").description("Search catalog").build(),
                Tool.builder("place_order").description("Place order").build(),
                Tool.builder("cancel_order").description("Cancel order").build(),
                Tool.builder("get_order_status").description("Order status").build()
        );
        when(mcpHub.discoverAllTools()).thenReturn(allTools);

        ModelResponse directReply = new ModelResponse("Found 3 chargers.", List.of());
        when(modelClient.generateResponse(anyList(), anyList())).thenReturn(directReply);

        AssistantChatService service = createChatService(modelClient, mcpHub);
        ChatMessageRequest request = new ChatMessageRequest("Find chargers in stock");
        ChatMessageResponse response = service.sendMessage(request, "user-123");

        assertThat(response.reply()).isEqualTo("Found 3 chargers.");

        ArgumentCaptor<List<Tool>> toolCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClient).generateResponse(anyList(), toolCaptor.capture());

        List<Tool> offeredTools = toolCaptor.getValue();
        assertThat(offeredTools).hasSize(1);
        assertThat(offeredTools.get(0).name()).isEqualTo("search_available_products");
    }

    @Test
    @DisplayName("WO-015: Should ask clarifying question when mutating intent has low confidence")
    void sendMessage_withMutatingIntentLowConfidence_asksClarifyingQuestionWithoutCallingTools() {
        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        when(mcpHub.discoverAllTools()).thenReturn(List.of(Tool.builder("place_order").build()));

        IntentResolver mockResolver = mock(IntentResolver.class);
        // Return mutating intent below threshold (0.60 < 0.92)
        when(mockResolver.resolve(anyString(), anyList()))
                .thenReturn(new vn.danang.polaris.assistant.intent.IntentClassification(
                        vn.danang.polaris.assistant.intent.IntentClassification.ORDER_PLACE, 0.60));

        AssistantChatService service = createChatService(
                modelClient, mcpHub, null, null, null, mockResolver, null, null);

        ChatMessageRequest request = new ChatMessageRequest("buy something maybe");
        ChatMessageResponse response = service.sendMessage(request, "user-123");

        assertThat(response).isNotNull();
        assertThat(response.reply()).contains("could you please clarify your request with specific details");
        // Model client should never be prompted for autonomous generation on low confidence mutating intents
        verify(modelClient, never()).generateResponse(anyList(), anyList());
        verify(mcpHub, never()).executeTool(anyString(), any());
    }

    @Test
    @DisplayName("WO-015: Should append corrective history message and continue loop when model calls invalid tool for intent")
    @SuppressWarnings("unchecked")
    void sendMessage_withToolMismatch_recordsWarningAndAppendsCorrectiveMessage() {
        ExternalMcpHub mcpHub = createMcpHub();
        Tool allowedTool = Tool.builder("search_available_products").build();
        Tool forbiddenTool = Tool.builder("cancel_order").build();
        doReturn(List.of(allowedTool, forbiddenTool)).when(mcpHub).discoverAllTools();

        // Turn 1: Model requests forbidden tool for catalog.product.search
        ModelResponse turn1Response = new ModelResponse("", List.of(new ToolCall("cancel_order", Map.of("order_id", "ORD-123"))));
        // Turn 2: Model self-corrects and returns text response
        ModelResponse turn2Response = new ModelResponse("Understood, I am looking up products instead.", List.of());

        when(modelClient.generateResponse(anyList(), anyList()))
                .thenReturn(turn1Response)
                .thenReturn(turn2Response);

        AssistantChatService service = createChatService(modelClient, mcpHub);
        ChatMessageRequest request = new ChatMessageRequest("Find chargers");
        ChatMessageResponse response = service.sendMessage(request, "user-123");

        assertThat(response.reply()).isEqualTo("Understood, I am looking up products instead.");

        // Verify that cancel_order was rejected and NOT executed
        verify(mcpHub, never()).executeTool(eq("cancel_order"), any());

        // Verify history captured the corrective tool turn
        ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList());

        List<AssistantMessage> turn2History = historyCaptor.getAllValues().get(1);
        assertThat(turn2History).hasSize(3);
        assertThat(turn2History.get(2).getRole()).isEqualTo(MessageRole.TOOL);
        assertThat(turn2History.get(2).getContent()).contains("not permitted for intent 'catalog.product.search'");
    }

    @Test
    @DisplayName("WO-015: Should short-circuit and return denial message when PolicyEngine denies scope")
    void sendMessage_withPolicyDenial_shortCircuitsAndReturnsDenialReason() {
        ExternalMcpHub mcpHub = createMcpHub();
        Tool tool = Tool.builder("place_order").build();
        doReturn(List.of(tool)).when(mcpHub).discoverAllTools();

        vn.danang.polaris.assistant.intent.PolicyEngine mockPolicy = mock(vn.danang.polaris.assistant.intent.PolicyEngine.class);
        when(mockPolicy.authorize(anyString(), eq("order.write")))
                .thenReturn(vn.danang.polaris.assistant.intent.PolicyDecision.deny("Missing scope 'order.write'."));

        ModelResponse turn1Response = new ModelResponse("", List.of(new ToolCall("place_order", Map.of("sku", "PROD-1"))));
        when(modelClient.generateResponse(anyList(), anyList())).thenReturn(turn1Response);

        AssistantChatService service = createChatService(
                modelClient, mcpHub, null, null, null, null, null, mockPolicy);

        ChatMessageRequest request = new ChatMessageRequest("buy the wireless earbuds");
        ChatMessageResponse response = service.sendMessage(request, "user-no-scope");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Action denied: Missing scope 'order.write'.");
        verify(mcpHub, never()).executeTool(anyString(), any());
    }

    @Test
    @DisplayName("WO-015: Should provide empty tool list for general conversation intent")
    @SuppressWarnings("unchecked")
    void sendMessage_withGeneralConversation_providesEmptyToolList() {
        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        when(mcpHub.discoverAllTools()).thenReturn(List.of(Tool.builder("search_available_products").build()));

        ModelResponse directReply = new ModelResponse("Hello! How can I assist you?", List.of());
        when(modelClient.generateResponse(anyList(), anyList())).thenReturn(directReply);

        AssistantChatService service = createChatService(modelClient, mcpHub);
        ChatMessageRequest request = new ChatMessageRequest("Hello there!");
        ChatMessageResponse response = service.sendMessage(request, "user-123");

        assertThat(response.reply()).isEqualTo("Hello! How can I assist you?");

        ArgumentCaptor<List<Tool>> toolCaptor = ArgumentCaptor.forClass(List.class);
        verify(modelClient).generateResponse(anyList(), toolCaptor.capture());
        assertThat(toolCaptor.getValue()).isEmpty();
    }

    // =========================================================================
    // WO-018 & ADR-0016: ReAct Orchestrator Wiring and Observability Tests
    // =========================================================================

    @Test
    @DisplayName("WO-018: ReAct loop passes ModelRequestContext with iteration and intent details to modelClient")
    @SuppressWarnings("unchecked")
    void reactLoop_passesModelRequestContextToClient() {
        ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
        Tool tool = Tool.builder("search_available_products").description("Search catalog").build();
        when(mcpHub.discoverAllTools()).thenReturn(List.of(tool));

        ModelResponse directReply = new ModelResponse("Found the products.", List.of());
        when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class))).thenReturn(directReply);

        AssistantChatService service = createChatService(modelClient, mcpHub);
        ChatMessageRequest request = new ChatMessageRequest("Find chargers in stock");
        ChatMessageResponse response = service.sendMessage(request, "user-123");

        assertThat(response.reply()).isEqualTo("Found the products.");

        ArgumentCaptor<ModelRequestContext> contextCaptor = ArgumentCaptor.forClass(ModelRequestContext.class);
        verify(modelClient).generateResponse(anyList(), anyList(), contextCaptor.capture());

        ModelRequestContext captured = contextCaptor.getValue();
        assertThat(captured).isNotNull();
        assertThat(captured.iteration()).isEqualTo(1);
        assertThat(captured.intentId()).isEqualTo("catalog.product.search");
        assertThat(captured.intentConfidence()).isGreaterThanOrEqualTo(0.9);
        assertThat(captured.toolsOfferedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("WO-018: ReAct loop emits enriched span events with tool names on agent.turn span")
    void reactLoop_emitsEnrichedSpanEventsWithToolNames() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ExternalMcpHub mcpHub = createMcpHub(tracer);
        Tool tool = Tool.builder("search_available_products").description("Search catalog").build();
        doReturn(List.of(tool)).when(mcpHub).discoverAllTools();
        doReturn(new CallToolResult(List.of(new TextContent("Charger 65W")), false, null, Map.of())).when(mcpHub).executeTool(eq("search_available_products"), any());

        ModelResponse turn1 = new ModelResponse("", List.of(new ToolCall("search_available_products", Map.of("query", "charger"))));
        ModelResponse turn2 = new ModelResponse("Found charger 65W.", List.of());
        when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                .thenReturn(turn1)
                .thenReturn(turn2);

        AssistantChatService service = createChatService(
                modelClient, mcpHub, tracer);

        ChatMessageRequest request = new ChatMessageRequest("sess-enriched-events", "Find charger");
        ChatMessageResponse response = service.sendMessage(request, "user-456");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Found charger 65W.");

        // Verify enriched event syntax containing tool name
        verify(span).event("agent.tool.call: search_available_products");
        verify(span).event("agent.tool.result: search_available_products");

        // Verify bare event syntax is never emitted
        verify(span, never()).event("agent.tool.call");
        verify(span, never()).event("agent.tool.result");
    }

    @Test
    @DisplayName("WO-018: ReAct loop invokes enriched recordToolExecution with audit metadata and arguments")
    void reactLoop_invokesEnrichedRecordToolExecution() {
        AgentDecisionRecorder mockRecorder = mock(AgentDecisionRecorder.class);
        ExternalMcpHub mcpHub = createMcpHub();
        Tool tool = Tool.builder("search_available_products").description("Search catalog").build();
        doReturn(List.of(tool)).when(mcpHub).discoverAllTools();

        CallToolResult expectedResult = new CallToolResult(List.of(new TextContent("Charger 65W")), false, null, Map.of());
        when(mockRecorder.recordToolExecution(
                anyString(), anyString(), anyDouble(), anyInt(), anyString(),
                anyBoolean(), anyString(), any(), any(), any(), anyList(), any(), any()))
                .thenReturn(expectedResult);

        ModelResponse turn1 = new ModelResponse("", List.of(new ToolCall("search_available_products", Map.of("query", "charger"))));
        ModelResponse turn2 = new ModelResponse("Found charger 65W.", List.of());
        when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                .thenReturn(turn1)
                .thenReturn(turn2);

        AssistantChatService service = createChatService(
                modelClient, mcpHub, null, null, mockRecorder, null, null, null);

        ChatMessageRequest request = new ChatMessageRequest("sess-record-audit", "Find charger");
        ChatMessageResponse response = service.sendMessage(request, "user-789");

        assertThat(response).isNotNull();

        verify(mockRecorder).recordToolExecution(
                eq("sess-record-audit"),
                eq("catalog.product.search"),
                eq(0.92),
                eq(1),
                eq("search_available_products"),
                eq(true),
                eq("ALLOW"),
                org.mockito.ArgumentMatchers.nullable(String.class),
                eq("catalog.read"),
                eq(Map.of("query", "charger")),
                anyList(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("WO-018: Thought signatures are logged with sampling and never attached to span attributes")
    void reactLoop_logsSampledThoughtSignatureWithoutAttachingToSpan() {
        Span span = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        Tracer tracer = mockTracerSetup(span, spanInScope);

        ExternalMcpHub mcpHub = createMcpHub(tracer);
        Tool tool = Tool.builder("search_available_products").description("Search catalog").build();
        doReturn(List.of(tool)).when(mcpHub).discoverAllTools();
        doReturn(new CallToolResult(List.of(new TextContent("Result")), false, null, Map.of())).when(mcpHub).executeTool(eq("search_available_products"), any());

        String secretThought = "THOUGHT_SIGNATURE_CONFIDENTIAL_12345";
        ModelResponse turn1 = new ModelResponse("", List.of(new ToolCall("search_available_products", Map.of("query", "test"), secretThought)));
        ModelResponse turn2 = new ModelResponse("Final reply.", List.of(), "THOUGHT_SIGNATURE_FINAL_67890");

        when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                .thenReturn(turn1)
                .thenReturn(turn2);

        AssistantChatService service = createChatService(
                modelClient, mcpHub, tracer);

        ChatMessageRequest request = new ChatMessageRequest("session-sampling-test", "Find products");
        ChatMessageResponse response = service.sendMessage(request, "user-test");

        assertThat(response).isNotNull();
        assertThat(response.reply()).isEqualTo("Final reply.");

        // CRITICAL PRIVACY VERIFICATION: Span tags must NEVER contain the thought signature or raw thoughts
        verify(span, never()).tag(eq("thoughtSignature"), anyString());
        verify(span, never()).tag(eq("gen_ai.thought"), anyString());
        verify(span, never()).tag(eq("thought"), anyString());
        verify(span, never()).tag(anyString(), eq(secretThought));
        verify(span, never()).tag(anyString(), eq("THOUGHT_SIGNATURE_FINAL_67890"));
    }

    @Test
    @DisplayName("Should delegate tool calls batch to McpHub and append returned messages to history")
    void sendMessage_withToolCalls_delegatesToMcpHubHandleToolCalls() {
        McpHub mockMcpHub = mock(McpHub.class);
        chatService = createChatService(modelClient, mockMcpHub);

        when(mockMcpHub.discoverAllTools()).thenReturn(List.of(Tool.builder("search_available_products").build()));

        AssistantMessage toolMsg = new AssistantMessage();
        toolMsg.setRole(MessageRole.TOOL);
        toolMsg.setContent("Found 1 product");
        when(mockMcpHub.handleToolCalls(anyList(), any(ToolExecutionContext.class)))
                .thenReturn(ToolExecutionResult.success(List.of(toolMsg)));

        ModelResponse turn1 = new ModelResponse("", List.of(new ToolCall("search_available_products", Map.of("query", "phone"))));
        ModelResponse turn2 = new ModelResponse("Found the phone.", List.of());
        when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                .thenReturn(turn1)
                .thenReturn(turn2);

        ChatMessageResponse response = chatService.sendMessage(new ChatMessageRequest("Search phone"), "user-123");
        assertThat(response.reply()).isEqualTo("Found the phone.");

        verify(mockMcpHub, times(1)).handleToolCalls(eq(turn1.toolCalls()), any(ToolExecutionContext.class));
    }
}
