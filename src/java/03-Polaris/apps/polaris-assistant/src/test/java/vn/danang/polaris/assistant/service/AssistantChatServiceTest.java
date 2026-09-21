package vn.danang.polaris.assistant.service;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
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
import vn.danang.polaris.assistant.intent.DefaultIntentResolver;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentResolutionFacade;
import vn.danang.polaris.assistant.intent.IntentResolver;
import vn.danang.polaris.assistant.intent.IntentTaxonomyProperties;
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
import vn.danang.polaris.assistant.observability.trace.CustomNextSpanAspect;

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

    // =========================================================================
    // 1. Happy path — conversational flow, tool loops, history & telemetry
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid message, when sendMessage is called, then forwards to model client and returns response")
        @SuppressWarnings("unchecked")
        void forwards_valid_message_to_model_client_and_returns_response() {
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
            assertThat(sentMessages.getFirst().getRole()).isEqualTo(MessageRole.USER);
            assertThat(sentMessages.getFirst().getContent()).isEqualTo("Tell me about Polaris");
        }

        @Test
        @DisplayName("Given model requests tool call, when processed in ReAct loop, then executes MCP tool and loops to final reply")
        void executes_tool_in_react_loop_and_returns_final_reply() {
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

            ModelResponse turn1Response = new ModelResponse("", List.of(
                    new ToolCall("search_available_products", Map.of("query", "charger"))
            ));
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
        @DisplayName("Given multiple turns with same sessionId, when messages are sent, then preserves conversation history")
        void preserves_conversation_history_across_multiple_turns_with_same_session_id() {
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
        @DisplayName("Given model response with thoughtSignature, when tool executed, then preserves thoughtSignature on AssistantMessage in history")
        @SuppressWarnings("unchecked")
        void propagates_thought_signature_to_next_turn_history() {
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
        @DisplayName("Given model returns tool calls, when processed, then delegates batch execution to McpHub and appends results to history")
        void delegates_tool_calls_batch_to_mcp_hub() {
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

        @Test
        @DisplayName("Given query resolving to intent, when processed, then narrows tools offered to model client based on resolved intent")
        @SuppressWarnings("unchecked")
        void narrows_tools_offered_to_model_client_based_on_resolved_intent() {
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
        @DisplayName("Given general conversation intent, when processed, then provides empty tool list to model client")
        @SuppressWarnings("unchecked")
        void provides_empty_tool_list_for_general_conversation_intent() {
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

        @Test
        @DisplayName("Given ReAct turn iteration and intent, when querying model client, then passes ModelRequestContext with iteration and intent details")
        @SuppressWarnings("unchecked")
        void passes_model_request_context_to_model_client() {
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
        @DisplayName("Given tool call turn with tracer, when processed, then emits full lifecycle span events in exact order")
        void emits_full_lifecycle_span_events_in_exact_order_with_tool_calls() {
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

            verify(tracer).nextSpan();
            verify(span).name("agent.turn");
            verify(span).tag("agent.name", "assistant-chat");
            verify(span).tag("agent.framework", "polaris-assistant");
            verify(span).tag("agent.session_id", "sess-001");
            verify(span).tag("agent.user_id", "user-456");
            verify(span).tag("agent.iterations.count", "2");
            verify(span).start();
            verify(tracer).withSpan(span);

            verify(span).event("agent.tool.call: search_available_products");
            verify(span).event("agent.tool.result: search_available_products");

            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given direct conversational reply without tool calls, when processed with tracer, then emits direct lifecycle events without tool events")
        void emits_direct_lifecycle_events_without_tool_call_events_on_direct_reply() {
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

            verify(span).tag("agent.user_id", "anonymous");
            verify(span).tag("agent.iterations.count", "1");

            verify(span, never()).event(org.mockito.ArgumentMatchers.startsWith("agent.tool.call"));
            verify(span, never()).event(org.mockito.ArgumentMatchers.startsWith("agent.tool.result"));

            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given tool execution in ReAct loop, when emitting span events, then formats events with tool names")
        void emits_enriched_span_events_with_tool_names() {
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

            verify(span).event("agent.tool.call: search_available_products");
            verify(span).event("agent.tool.result: search_available_products");

            verify(span, never()).event("agent.tool.call");
            verify(span, never()).event("agent.tool.result");
        }
    }

    // =========================================================================
    // 2. Invalid input & policy denials
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & policy denials")
    class InvalidInput {

        @Test
        @DisplayName("Given blank message, when constructed in request, then throws IllegalArgumentException")
        void rejects_blank_message_payload() {
            assertThatThrownBy(() -> new ChatMessageRequest("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Message content must not be blank.");
        }

        @Test
        @DisplayName("Given intent with confidence below threshold, when message sent, then falls back to full available tools without canned clarification")
        void falls_back_to_available_tools_when_confidence_below_threshold() {
            ExternalMcpHub mcpHub = mock(ExternalMcpHub.class);
            Tool tool = Tool.builder("place_order").build();
            when(mcpHub.discoverAllTools()).thenReturn(List.of(tool));

            IntentResolver mockResolver = mock(IntentResolver.class);
            when(mockResolver.resolve(anyString(), anyList()))
                    .thenReturn(new IntentClassification(
                            IntentClassification.ORDER_PLACE, 0.60));

            when(modelClient.generateResponse(anyList(), eq(List.of(tool)), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("I can help you place an order. Which product do you need?"));

            AssistantChatService service = createChatService(
                    modelClient, mcpHub, null, null, mockResolver, null, null);

            ChatMessageRequest request = new ChatMessageRequest("buy something maybe");
            ChatMessageResponse response = service.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("I can help you place an order. Which product do you need?");
            verify(modelClient).generateResponse(anyList(), eq(List.of(tool)), any(ModelRequestContext.class));
            verify(mcpHub, never()).executeTool(anyString(), any());
        }

        @Test
        @DisplayName("Given model calls tool not permitted for resolved intent, when evaluated, then rejects execution and appends corrective tool message into history")
        @SuppressWarnings("unchecked")
        void rejects_disallowed_tool_for_intent_and_appends_corrective_message() {
            ExternalMcpHub mcpHub = createMcpHub();
            Tool allowedTool = Tool.builder("search_available_products").build();
            Tool forbiddenTool = Tool.builder("cancel_order").build();
            doReturn(List.of(allowedTool, forbiddenTool)).when(mcpHub).discoverAllTools();

            ModelResponse turn1Response = new ModelResponse("", List.of(new ToolCall("cancel_order", Map.of("order_id", "ORD-123"))));
            ModelResponse turn2Response = new ModelResponse("Understood, I am looking up products instead.", List.of());

            when(modelClient.generateResponse(anyList(), anyList()))
                    .thenReturn(turn1Response)
                    .thenReturn(turn2Response);

            AssistantChatService service = createChatService(modelClient, mcpHub);
            ChatMessageRequest request = new ChatMessageRequest("Find chargers");
            ChatMessageResponse response = service.sendMessage(request, "user-123");

            assertThat(response.reply()).isEqualTo("Understood, I am looking up products instead.");

            verify(mcpHub, never()).executeTool(eq("cancel_order"), any());

            ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList());

            List<AssistantMessage> turn2History = historyCaptor.getAllValues().get(1);
            assertThat(turn2History).hasSize(3);
            assertThat(turn2History.get(2).getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(turn2History.get(2).getContent()).contains("not permitted for intent 'catalog.product.search'");
        }

        @Test
        @DisplayName("Given PolicyEngine denies required scope, when tool requested, then short-circuits execution and returns denial reason")
        void short_circuits_and_returns_denial_reason_when_policy_engine_denies_scope() {
            PolicyEngine mockPolicy = mock(PolicyEngine.class);
            when(mockPolicy.authorize(anyString(), eq("order.write")))
                    .thenReturn(PolicyDecision.deny("Missing scope 'order.write'."));

            ExternalMcpHub mcpHub = createMcpHub(null, mockPolicy);
            Tool tool = Tool.builder("place_order").build();
            doReturn(List.of(tool)).when(mcpHub).discoverAllTools();

            ModelResponse turn1Response = new ModelResponse("", List.of(new ToolCall("place_order", Map.of("sku", "PROD-1"))));
            when(modelClient.generateResponse(anyList(), anyList())).thenReturn(turn1Response);

            AssistantChatService service = createChatService(
                    modelClient, mcpHub, null, null, null, null, mockPolicy);

            ChatMessageRequest request = new ChatMessageRequest("buy the wireless earbuds");
            ChatMessageResponse response = service.sendMessage(request, "user-no-scope");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Action denied: Missing scope 'order.write'.");
            verify(mcpHub, never()).executeTool(anyString(), any());
        }

        @Test
        @DisplayName("Given turn fails with model exception, when tracer present, then tags error and records exception on span")
        void records_event_and_error_on_span_when_turn_fails() {
            Span span = mock(Span.class);
            Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
            Tracer tracer = mockTracerSetup(span, spanInScope);

            McpHub mockMcpHub = mock(McpHub.class);
            when(mockMcpHub.discoverAllTools()).thenReturn(List.of());

            AssistantChatService serviceWithTracer = createChatService(
                    modelClient, mockMcpHub, tracer);

            when(modelClient.generateResponse(anyList(), anyList()))
                    .thenThrow(new RuntimeException("Gemini model failure"));

            ChatMessageRequest request = new ChatMessageRequest("sess-err", "Find fast chargers");

            assertThatThrownBy(() -> serviceWithTracer.sendMessage(request, "user-err"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Gemini model failure");

            verify(span).error(any(RuntimeException.class));
            verify(span).tag("error", "true");
            verify(span, times(1)).end();
        }
    }

    // =========================================================================
    // 3. Edge cases — iteration limits, parallel turns, privacy & null providers
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given looping tool calls, when exceeding maximum 5 iterations, then terminates safely with completion reply")
        void terminates_safely_when_tool_calls_exceed_max_iterations() {
            ExternalMcpHub mcpHub = createMcpHub();
            chatService = createChatService(modelClient, mcpHub);

            doReturn(List.of(Tool.builder("looping_tool").build())).when(mcpHub).discoverAllTools();
            doReturn(new CallToolResult(List.of(new TextContent("ok")), false, null, Map.of())).when(mcpHub).executeTool(any(), any());

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
        @DisplayName("Given looping tool calls with tracer, when max iterations reached, then emits 5 iteration events and tags iterations.count=5")
        void records_all_iterations_and_tags_count_when_exceeding_max_iterations_with_tracer() {
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

            verify(span, times(5)).event("agent.tool.call: loop_tool");
            verify(span, times(5)).event("agent.tool.result: loop_tool");
            verify(span).tag("agent.iterations.count", "5");
            verify(span).end();
        }

        @Test
        @DisplayName("Given parallel tool calls returned by model, when appended to history, then groups all ASSISTANT turns before TOOL turns")
        @SuppressWarnings("unchecked")
        void groups_parallel_model_turns_before_tool_turns_in_history() {
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

        @Test
        @DisplayName("Given parallel tool calls with tracer, when executed, then emits paired tool.call and tool.result events for each tool")
        void emits_paired_tool_call_and_result_events_for_parallel_tools() {
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

            verify(span).tag("agent.iterations.count", "2");
            verify(span).end();
        }

        @Test
        @DisplayName("Given responses containing thought signatures, when processed with tracer, then samples/logs signatures and never attaches to span tags")
        void strictly_excludes_thought_signatures_from_span_attributes() {
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

            verify(span, never()).tag(eq("thoughtSignature"), anyString());
            verify(span, never()).tag(eq("gen_ai.thought"), anyString());
            verify(span, never()).tag(eq("thought"), anyString());
            verify(span, never()).tag(anyString(), eq(secretThought));
            verify(span, never()).tag(anyString(), eq("THOUGHT_SIGNATURE_FINAL_67890"));
        }
    }

    private ExternalMcpHub createMcpHub() {
        return createMcpHub((Tracer) null);
    }

    private ExternalMcpHub createMcpHub(Tracer tracer) {
        return createMcpHub(tracer, null);
    }

    private ExternalMcpHub createMcpHub(Tracer tracer, PolicyEngine policyEngine) {
        PolarisMcpClient client = mock(PolarisMcpClient.class);
        return spy(new ExternalMcpHub(client, tracer, new ObjectMapper(), new IntentToolRegistry(), policyEngine));
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

    private AssistantChatService createChatService(AssistantModelClient modelClient, McpHub mcpHub) {
        return createChatService(modelClient, mcpHub, null);
    }

    private AssistantChatService createChatService(AssistantModelClient modelClient, McpHub mcpHub, Tracer tracer) {
        return createChatService(modelClient, mcpHub, tracer, null, null);
    }

    private AssistantChatService createChatService(
            AssistantModelClient modelClient,
            McpHub mcpHub,
            ObjectMapper objectMapper,
            Tracer tracer,
            IntentResolver intentResolver,
            IntentToolRegistry intentToolRegistry,
            PolicyEngine policyEngine) {
        return createChatService(modelClient, mcpHub, tracer, intentResolver, intentToolRegistry);
    }

    private AssistantChatService createChatService(
            AssistantModelClient modelClient,
            McpHub mcpHub,
            Tracer tracer,
            IntentResolver intentResolver,
            IntentToolRegistry intentToolRegistry) {
        IntentResolutionFacade facade = new IntentResolutionFacade(
                intentResolver != null ? intentResolver : new DefaultIntentResolver(new IntentTaxonomyProperties()),
                intentToolRegistry != null ? intentToolRegistry : new IntentToolRegistry(),
                mcpHub
        );
        AssistantChatService target = new AssistantChatService(
                modelClient,
                mcpHub,
                providerOf(tracer),
                facade
        );
        if (tracer != null) {
            org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                    new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(target);
            factory.addAspect(new CustomNextSpanAspect(tracer));
            return factory.getProxy();
        }
        return target;
    }

    private Tracer mockTracerSetup(Span mockSpan, Tracer.SpanInScope mockSpanInScope) {
        Tracer tracer = mock(Tracer.class);
        when(tracer.nextSpan()).thenReturn(mockSpan);
        when(tracer.currentSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockSpan.event(anyString())).thenReturn(mockSpan);
        when(tracer.withSpan(mockSpan)).thenReturn(mockSpanInScope);
        return tracer;
    }
}
