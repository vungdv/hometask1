package vn.danang.polaris.assistant.service;

import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * Unit tests for {@link AssistantChatService#runStreamingTurn}, the package-visible core of
 * {@link AssistantChatService#streamTurn} (WO-020 Task 8) — verified directly with a recording
 * {@link SseEmitter} subclass rather than a real HTTP round trip, so each event's exact name/data
 * is directly assertable.
 */
class AssistantChatServiceStreamTurnTest {

    private AssistantModelClient modelClient;
    private IntentResolutionFacade intentResolutionFacade;
    private AssistantChatService chatService;

    @BeforeEach
    void setUp() {
        modelClient = mock(AssistantModelClient.class);
        intentResolutionFacade = mock(IntentResolutionFacade.class);
        when(intentResolutionFacade.resolve(anyString(), anyList()))
                .thenReturn(new ResolvedIntent("commerce.order.place", 1.0, true, List.of(),
                        new IntentDefinition("commerce.order.place", "d", List.of(), List.of("stage_order_draft"), "order.write", 0.8, true)));

        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        chatService = new AssistantChatService(modelClient, tracerProvider, intentResolutionFacade, new ObjectMapper());
    }

    /** Captures each {@code emitter.send(SseEventBuilder)} call as a (eventName, data) pair, without any real transport. */
    private static final class RecordingSseEmitter extends SseEmitter {
        final List<SimpleEntry<String, Object>> events = new CopyOnWriteArrayList<>();
        volatile boolean completed;
        volatile Throwable error;

        @Override
        public void send(SseEventBuilder builder) {
            String name = null;
            Object data = null;
            for (DataWithMediaType part : builder.build()) {
                // The builder concatenates "event:<name>\n" with the following "data:" prefix into
                // a single text chunk (see SseEmitter.SseEventBuilderImpl) — note its internal
                // TEXT_PLAIN constant carries a charset parameter, so it deliberately isn't
                // compared against MediaType.TEXT_PLAIN here; matching on content is more robust.
                if (part.getData() instanceof String text && text.startsWith("event:")) {
                    int newlineIndex = text.indexOf('\n');
                    name = newlineIndex >= 0 ? text.substring("event:".length(), newlineIndex) : text.substring("event:".length());
                } else if (!(part.getData() instanceof String text2 && text2.isBlank())) {
                    data = part.getData();
                }
            }
            events.add(new SimpleEntry<>(name, data));
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            error = ex;
            completed = true;
        }

        Object dataFor(String eventName) {
            return events.stream().filter(e -> eventName.equals(e.getKey())).map(SimpleEntry::getValue).findFirst().orElse(null);
        }
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a turn with no tool calls, when streamed, then thought/token/done frames are emitted and the emitter completes")
        void runStreamingTurn_noToolCalls_emitsTokenAndDoneThenCompletes() {
            when(modelClient.generateResponse(anyList(), anyList(), any()))
                    .thenReturn(new ModelResponse("Hello there!", List.of()));

            RecordingSseEmitter emitter = new RecordingSseEmitter();
            chatService.runStreamingTurn(emitter, "sess-1", "hi", "user-1");

            assertThat(emitter.events).extracting(SimpleEntry::getKey).contains("thought", "token", "done");
            assertThat(((Map<?, ?>) emitter.dataFor("token")).get("delta")).isEqualTo("Hello there!");
            assertThat(emitter.completed).isTrue();
            assertThat(emitter.error).isNull();
        }

        @Test
        @DisplayName("Given a stage_order_draft tool call that stages successfully, when streamed, then a draft event carries the staged summary")
        void runStreamingTurn_stagedDraft_emitsDraftEvent() {
            ToolCall toolCall = new ToolCall("call-1", "stage_order_draft", Map.of("customer_id", 42, "items", List.of()));
            when(modelClient.generateResponse(anyList(), anyList(), any()))
                    .thenReturn(new ModelResponse(null, List.of(toolCall)))
                    .thenReturn(new ModelResponse("Staged your order.", List.of()));

            Map<String, Object> draftData = Map.of("draftId", "dft-abc", "status", "WAITING_CONFIRMATION");
            ToolResult staged = ToolResult.success(toolCall, "Staged order draft dft-abc", draftData);
            when(intentResolutionFacade.executeToolCalls(anyList(), any(ToolExecutionContext.class)))
                    .thenReturn(List.of(staged));

            RecordingSseEmitter emitter = new RecordingSseEmitter();
            chatService.runStreamingTurn(emitter, "sess-2", "order 2 earbuds", "user-1");

            assertThat(emitter.dataFor("draft")).isEqualTo(draftData);
            assertThat(emitter.completed).isTrue();
        }
    }

    // =========================================================================
    // 2. Invalid input / policy denial
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & policy denial")
    class InvalidInput {

        @Test
        @DisplayName("Given a tool call denied by policy, when streamed, then the turn ends with the denial message as the token frame")
        void runStreamingTurn_policyDenied_emitsDenialAsTokenAndCompletes() {
            ToolCall toolCall = new ToolCall("call-1", "stage_order_draft", Map.of());
            when(modelClient.generateResponse(anyList(), anyList(), any()))
                    .thenReturn(new ModelResponse(null, List.of(toolCall)));
            ToolResult denied = ToolResult.denied(toolCall, "Scope missing");
            when(intentResolutionFacade.executeToolCalls(anyList(), any(ToolExecutionContext.class)))
                    .thenReturn(List.of(denied));

            RecordingSseEmitter emitter = new RecordingSseEmitter();
            chatService.runStreamingTurn(emitter, "sess-3", "order something", "user-1");

            assertThat(((Map<?, ?>) emitter.dataFor("token")).get("delta")).isEqualTo("Action denied: Scope missing");
            assertThat(emitter.completed).isTrue();
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given a stage_order_draft tool call that is rejected for insufficient stock, when streamed, then a problem event carries the remedy actions")
        void runStreamingTurn_rejectedDraft_emitsProblemEventWithActions() {
            ToolCall toolCall = new ToolCall("call-1", "stage_order_draft", Map.of());
            when(modelClient.generateResponse(anyList(), anyList(), any()))
                    .thenReturn(new ModelResponse(null, List.of(toolCall)))
                    .thenReturn(new ModelResponse("Let's adjust the quantity.", List.of()));

            List<Map<String, Object>> actions = List.of(Map.of("label", "Remove Item", "action", "remove_item"));
            Map<String, Object> problemData = Map.of("sku", "NG-WATCH-01", "requested_quantity", 10, "available_quantity", 3);
            ToolResult rejected = ToolResult.error(toolCall, "Insufficient stock", "insufficient stock", actions, problemData);
            when(intentResolutionFacade.executeToolCalls(anyList(), any(ToolExecutionContext.class)))
                    .thenReturn(List.of(rejected));

            RecordingSseEmitter emitter = new RecordingSseEmitter();
            chatService.runStreamingTurn(emitter, "sess-4", "order 10 watches", "user-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> problem = (Map<String, Object>) emitter.dataFor("problem");
            assertThat(problem).containsEntry("sku", "NG-WATCH-01").containsEntry("actions", actions);
        }

        @Test
        @DisplayName("Given the model client throws, when streamed, then the emitter completes with the error rather than propagating it")
        void runStreamingTurn_modelThrows_completesWithError() {
            when(modelClient.generateResponse(anyList(), anyList(), any())).thenThrow(new RuntimeException("model unavailable"));

            RecordingSseEmitter emitter = new RecordingSseEmitter();
            chatService.runStreamingTurn(emitter, "sess-5", "hi", "user-1");

            assertThat(emitter.completed).isTrue();
            assertThat(emitter.error).isInstanceOf(RuntimeException.class).hasMessage("model unavailable");
        }
    }
}
