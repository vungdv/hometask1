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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentResolutionFacade;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.mcp.ToolExecutionContext;
import vn.danang.polaris.assistant.mcp.ToolResult;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpanAspect;

/**
 * Unit tests for {@link AssistantChatService}.
 * <p>
 * SUT Responsibility: Conversational agent orchestrator managing the conversation lifecycle,
 * intent resolution delegation, ReAct loop iteration bounds, and tool batch delegation to {@link IntentResolutionFacade}.
 * Low-level execution details (MCP communication, policy enforcement rules, intent classification algorithms)
 * are encapsulated within collaborators and tested at their respective seam contracts.
 */
class AssistantChatServiceTest {

    private AssistantModelClient modelClient;
    private IntentResolutionFacade intentResolutionFacade;
    private AssistantChatService chatService;

    @BeforeEach
    void setUp() {
        modelClient = mock(AssistantModelClient.class);
        intentResolutionFacade = mock(IntentResolutionFacade.class);

        when(intentResolutionFacade.resolve(anyString(), anyList()))
                .thenReturn(new ResolvedIntent(
                        "general.conversation",
                        1.0,
                        true,
                        List.of()
                ));

        chatService = createChatService(modelClient, null, intentResolutionFacade);
    }

    // =========================================================================
    // 1. Happy path — conversational flow, tool loops, history & orchestration
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid message, when sendMessage is called, then forwards to model client and returns response")
        @SuppressWarnings("unchecked")
        void forwards_valid_message_to_model_client_and_returns_response() {
            ChatMessageRequest request = ChatMessageRequest.of("Tell me about Polaris");
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Polaris is an enterprise ecommerce platform.", List.of()));

            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.role()).isEqualTo("ASSISTANT");
            assertThat(response.reply()).isEqualTo("Polaris is an enterprise ecommerce platform.");
            assertThat(response.createdAt()).isNotNull();

            ArgumentCaptor<List<AssistantMessage>> captor = ArgumentCaptor.forClass(List.class);
            verify(modelClient).generateResponse(captor.capture(), anyList(), any(ModelRequestContext.class));

            List<AssistantMessage> sentMessages = captor.getValue();
            assertThat(sentMessages).hasSize(1);
            assertThat(sentMessages.getFirst().getRole()).isEqualTo(MessageRole.USER);
            assertThat(sentMessages.getFirst().getContent()).isEqualTo("Tell me about Polaris");
            verify(intentResolutionFacade).resolve(eq("Tell me about Polaris"), anyList());
        }

        @Test
        @DisplayName("Given model requests tool call, when processed in ReAct loop, then delegates to IntentResolutionFacade and loops to final reply")
        void executes_tool_in_react_loop_and_returns_final_reply() {
            ToolCall toolCall = new ToolCall("search_available_products", Map.of("query", "charger"));
            ModelResponse turn1Response = new ModelResponse("", List.of(toolCall));
            ModelResponse turn2Response = new ModelResponse("I found the Fast Charger 65W for $24.90.", List.of());

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(turn1Response)
                    .thenReturn(turn2Response);

            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class)))
                    .thenReturn(List.of(ToolResult.success(toolCall, "Found: Fast Charger 65W ($24.90)")));

            ChatMessageRequest request = ChatMessageRequest.of("Find fast chargers");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("I found the Fast Charger 65W for $24.90.");
            verify(intentResolutionFacade, times(1)).executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class));
            verify(modelClient, times(2)).generateResponse(anyList(), anyList(), any(ModelRequestContext.class));
        }

        @Test
        @DisplayName("Given multiple turns with same sessionId, when messages are sent, then preserves conversation history")
        @SuppressWarnings("unchecked")
        void preserves_conversation_history_across_multiple_turns_with_same_session_id() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Hello! How can I help you?", List.of()))
                    .thenReturn(new ModelResponse("Da Nang is a coastal city in Vietnam.", List.of()));

            String sessionId = "session-persist-100";
            ChatMessageRequest msg1 = ChatMessageRequest.of(sessionId, "Hi");
            ChatMessageResponse resp1 = chatService.sendMessage(msg1, "user-123");
            assertThat(resp1.reply()).isEqualTo("Hello! How can I help you?");

            ChatMessageRequest msg2 = ChatMessageRequest.of(sessionId, "Tell me about Da Nang");
            ChatMessageResponse resp2 = chatService.sendMessage(msg2, "user-123");
            assertThat(resp2.reply()).isEqualTo("Da Nang is a coastal city in Vietnam.");

            ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList(), any(ModelRequestContext.class));

            List<AssistantMessage> secondTurnHistory = historyCaptor.getAllValues().get(1);
            assertThat(secondTurnHistory).hasSize(3);
            assertThat(secondTurnHistory.get(0).getContent()).isEqualTo("Hi");
            assertThat(secondTurnHistory.get(1).getContent()).isEqualTo("Hello! How can I help you?");
            assertThat(secondTurnHistory.get(2).getContent()).isEqualTo("Tell me about Da Nang");
        }

        @Test
        @DisplayName("Given parallel tool calls returned by model, when appended to history, then groups all ASSISTANT turns before TOOL turns")
        @SuppressWarnings("unchecked")
        void groups_parallel_model_turns_before_tool_turns_in_history() {
            ToolCall toolCall1 = new ToolCall("search_products", Map.of("query", "charger"), "sig_parallel_call");
            ToolCall toolCall2 = new ToolCall("search_promotions", Map.of("category", "all"), null);

            ModelResponse turn1Response = new ModelResponse("", List.of(toolCall1, toolCall2));
            ModelResponse turn2Response = new ModelResponse("Found charger with 10% discount.", List.of());

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(turn1Response)
                    .thenReturn(turn2Response);

            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall1, toolCall2)), any(ToolExecutionContext.class)))
                    .thenReturn(List.of(
                            ToolResult.success(toolCall1, "Charger"),
                            ToolResult.success(toolCall2, "10% off")
                    ));

            ChatMessageRequest request = ChatMessageRequest.of("Search charger and deals");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Found charger with 10% discount.");

            ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList(), any(ModelRequestContext.class));

            List<AssistantMessage> turn2History = historyCaptor.getAllValues().get(1);
            assertThat(turn2History).hasSize(5);

            assertThat(turn2History.get(0).getRole()).isEqualTo(MessageRole.USER);
            assertThat(turn2History.get(0).getContent()).isEqualTo("Search charger and deals");

            assertThat(turn2History.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(turn2History.get(1).getToolCallId()).isEqualTo("search_products");
            assertThat(turn2History.get(1).getThoughtSignature()).isEqualTo("sig_parallel_call");

            assertThat(turn2History.get(2).getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(turn2History.get(2).getToolCallId()).isEqualTo("search_promotions");

            assertThat(turn2History.get(3).getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(turn2History.get(3).getToolCallId()).isEqualTo("search_products");
            assertThat(turn2History.get(3).getContent()).isEqualTo("Charger");

            assertThat(turn2History.get(4).getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(turn2History.get(4).getToolCallId()).isEqualTo("search_promotions");
            assertThat(turn2History.get(4).getContent()).isEqualTo("10% off");
        }
    }

    // =========================================================================
    // 2. Invalid input & policy denials
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & policy denials")
    class InvalidInput {

        @Test
        @DisplayName("Given model requests tool call and facade tool execution is denied, when processed, then short-circuits ReAct loop and returns denial reason")
        void short_circuits_and_returns_denial_reason_when_mcp_tool_execution_is_denied() {
            ToolCall toolCall = new ToolCall("place_order", Map.of("sku", "PROD-1"));
            ModelResponse turn1Response = new ModelResponse("", List.of(toolCall));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(turn1Response);

            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class)))
                    .thenReturn(List.of(ToolResult.denied(toolCall, "Missing scope 'order.write'.")));

            ChatMessageRequest request = ChatMessageRequest.of("buy wireless earbuds");
            ChatMessageResponse response = chatService.sendMessage(request, "user-no-scope");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Action denied: Missing scope 'order.write'.");
            verify(modelClient, times(1)).generateResponse(anyList(), anyList(), any(ModelRequestContext.class));
            verify(intentResolutionFacade, times(1)).executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class));
        }

        @Test
        @DisplayName("Given turn fails with model exception, when tracer present, then tags error and records exception on span")
        void records_error_and_propagates_exception_when_model_fails() {
            Span span = mock(Span.class);
            Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
            Tracer tracer = mockTracerSetup(span, spanInScope);

            AssistantChatService serviceWithTracer = createChatService(modelClient, tracer, intentResolutionFacade);

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenThrow(new RuntimeException("Gemini model failure"));

            ChatMessageRequest request = ChatMessageRequest.of("sess-err", "Find fast chargers");

            assertThatThrownBy(() -> serviceWithTracer.sendMessage(request, "user-err"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Gemini model failure");

            verify(span).error(any(RuntimeException.class));
            verify(span).tag("error", "true");
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given primary context call returns null, when executed, then falls back to overloaded generateResponse or chat")
        void falls_back_to_alternate_model_calls_when_primary_context_call_returns_null() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(null);
            when(modelClient.generateResponse(anyList(), anyList()))
                    .thenReturn(null);
            when(modelClient.chat(anyList()))
                    .thenReturn("Fallback chat reply");

            ChatMessageRequest request = ChatMessageRequest.of("Hello fallback");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Fallback chat reply");
            verify(modelClient).chat(anyList());
        }
    }

    // =========================================================================
    // 3. Edge cases — iteration limits, telemetry tags, and metadata contexts
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given looping tool calls, when exceeding maximum 5 iterations, then terminates safely with completion reply")
        void terminates_safely_when_tool_calls_exceed_max_iterations() {
            ToolCall loopCall = new ToolCall("looping_tool", Map.of());
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(loopCall)));

            when(intentResolutionFacade.executeToolCalls(eq(List.of(loopCall)), any(ToolExecutionContext.class)))
                    .thenReturn(List.of(ToolResult.success(loopCall, "ok")));

            ChatMessageRequest request = ChatMessageRequest.of("Run loop");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("I have completed processing your request.");
            verify(modelClient, times(5)).generateResponse(anyList(), anyList(), any(ModelRequestContext.class));
            verify(intentResolutionFacade, times(5)).executeToolCalls(eq(List.of(loopCall)), any(ToolExecutionContext.class));
        }

        @Test
        @DisplayName("Given active tracer, when ReAct turn completes, then tags agent.iterations.count on active span")
        void tags_iteration_count_on_active_span_when_tracer_is_present() {
            Span span = mock(Span.class);
            Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
            Tracer tracer = mockTracerSetup(span, spanInScope);

            AssistantChatService serviceWithTracer = createChatService(modelClient, tracer, intentResolutionFacade);

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Direct response", List.of()));

            ChatMessageRequest request = ChatMessageRequest.of("sess-tracer", "Hello");
            ChatMessageResponse response = serviceWithTracer.sendMessage(request, "user-trace");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Direct response");
            verify(span).tag("agent.iterations.count", "1");
            verify(span).tag("agent.user_id", "user-trace");
            verify(span).tag("agent.session_id", "sess-tracer");
            verify(span).end();
        }

        @Test
        @DisplayName("Given model response with thoughtSignature, when tool executed, then preserves thoughtSignature on AssistantMessage in history")
        @SuppressWarnings("unchecked")
        void propagates_thought_signature_from_tool_call_to_history() {
            ToolCall toolCall = new ToolCall("default_api:search_available_products", Map.of("query", "charger"), "sig_token_xyz789");
            ModelResponse turn1Response = new ModelResponse("", List.of(toolCall));
            ModelResponse turn2Response = new ModelResponse("I found the charger for you.", List.of(), "sig_final_reply_token");

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(turn1Response)
                    .thenReturn(turn2Response);

            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class)))
                    .thenReturn(List.of(ToolResult.success(toolCall, "Product found: Charger")));

            ChatMessageRequest request = ChatMessageRequest.of("Search for charger");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("I found the charger for you.");

            ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList(), any(ModelRequestContext.class));

            List<AssistantMessage> turn2History = historyCaptor.getAllValues().get(1);
            assertThat(turn2History).hasSize(3);

            AssistantMessage modelTurn = turn2History.get(1);
            assertThat(modelTurn.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(modelTurn.getToolCallId()).isEqualTo("default_api:search_available_products");
            assertThat(modelTurn.getThoughtSignature()).isEqualTo("sig_token_xyz789");
        }

        @Test
        @DisplayName("Given ReAct turn iteration and intent, when querying model client, then passes ModelRequestContext with iteration and intent details")
        void passes_model_request_context_with_iteration_and_intent_metadata() {
            Tool tool = Tool.builder("search_available_products", Map.of()).description("Search catalog").build();
            ResolvedIntent resolved = new ResolvedIntent(
                    "catalog.product.search",
                    0.95,
                    true,
                    List.of(tool)
            );
            when(intentResolutionFacade.resolve(eq("Find chargers in stock"), anyList()))
                    .thenReturn(resolved);

            when(modelClient.generateResponse(anyList(), eq(List.of(tool)), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Found the products.", List.of()));

            ChatMessageRequest request = ChatMessageRequest.of("Find chargers in stock");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response.reply()).isEqualTo("Found the products.");

            ArgumentCaptor<ModelRequestContext> contextCaptor = ArgumentCaptor.forClass(ModelRequestContext.class);
            verify(modelClient).generateResponse(anyList(), eq(List.of(tool)), contextCaptor.capture());

            ModelRequestContext captured = contextCaptor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.iteration()).isEqualTo(1);
            assertThat(captured.intentId()).isEqualTo("catalog.product.search");
            assertThat(captured.intentConfidence()).isEqualTo(0.95);
            assertThat(captured.toolsOfferedCount()).isEqualTo(1);
        }
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

    private AssistantChatService createChatService(
            AssistantModelClient modelClient,
            Tracer tracer,
            IntentResolutionFacade facade) {
        AssistantChatService target = new AssistantChatService(
                modelClient,
                providerOf(tracer),
                facade != null ? facade : intentResolutionFacade,
                new ObjectMapper()
        );
        if (tracer != null) {
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
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
