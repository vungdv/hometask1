package vn.danang.polaris.assistant.observability;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

class AgentDecisionRecorderTest {

    private ObjectMapper objectMapper;
    private Tracer tracer;
    private Span span;
    private TraceContext traceContext;
    private AgentDecisionRecorder recorder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        tracer = mock(Tracer.class);
        span = mock(Span.class);
        traceContext = mock(TraceContext.class);

        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
        when(traceContext.spanId()).thenReturn("0123456789abcdef");
        when(span.tag(anyString(), anyString())).thenReturn(span);

        recorder = new AgentDecisionRecorder(objectMapper, tracer);
    }

    @Test
    @DisplayName("WO-014: recordDirectResponseDecision records success event and tags active span")
    void recordDirectResponseDecision_recordsSuccessEvent() {
        Tool tool1 = Tool.builder("search_products").description("Search products").build();
        Tool tool2 = Tool.builder("view_cart").description("View user cart").build();

        DecisionEvent event = recorder.recordDirectResponseDecision(
                "session-100", "Hello there", List.of(tool1, tool2));

        assertThat(event).isNotNull();
        assertThat(event.selectedAction()).isEqualTo("reply_to_user");
        assertThat(event.intent()).isEqualTo("Hello there");
        assertThat(event.sessionId()).isEqualTo("session-100");
        assertThat(event.traceId()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(event.spanId()).isEqualTo("0123456789abcdef");
        assertThat(event.outcome()).isNotNull();
        assertThat(event.outcome().status()).isEqualTo("SUCCESS");
        assertThat(event.outcome().detail()).isEqualTo("Direct conversational response generated");

        // Verify span tags
        verify(span).tag("decision.action", "reply_to_user");
        verify(span).tag("decision.intent", "Hello there");
        verify(span).tag("decision.confidence", "1.0");
        verify(span).tag("decision.policy", "MAX_TOOL_ITERATIONS=5");
        verify(span).tag("decision.outcome.status", "SUCCESS");
        verify(span).tag("decision.outcome.detail", "Direct conversational response generated");
    }

    @Test
    @DisplayName("WO-014: recordToolExecution executes supplier and records success outcome")
    void recordToolExecution_success_returnsResult() {
        Tool tool = Tool.builder("search_products").description("Search catalog").build();
        CallToolResult expectedResult = new CallToolResult(
                List.of(new TextContent("Found: Charger")),
                false,
                null,
                Map.of()
        );

        CallToolResult actual = recorder.recordToolExecution(
                "session-200",
                "Find chargers",
                "search_products",
                List.of(tool),
                () -> expectedResult
        );

        assertThat(actual).isSameAs(expectedResult);
        verify(span).tag("decision.action", "search_products");
        verify(span).tag("decision.intent", "Find chargers");
        verify(span).tag("decision.outcome.status", "SUCCESS");
        verify(span).tag("decision.outcome.detail", "Found: Charger");
    }

    @Test
    @DisplayName("WO-014: recordToolExecution records failure outcome and rethrows when supplier throws")
    void recordToolExecution_withException_recordsFailureAndRethrows() {
        Tool tool = Tool.builder("failing_tool").build();

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                recorder.recordToolExecution(
                        "session-err",
                        "Run failing tool",
                        "failing_tool",
                        List.of(tool),
                        () -> {
                            throw new RuntimeException("Connection timeout");
                        }
                )
        );

        assertThat(thrown).hasMessage("Connection timeout");
        verify(span).tag("decision.action", "failing_tool");
        verify(span).tag("decision.outcome.status", "FAILURE");
        verify(span).tag("decision.outcome.detail", "Connection timeout");
    }

    @Test
    @DisplayName("WO-014: recordToolExecution records failure outcome when tool result has isError=true")
    void recordToolExecution_withErrorResult_recordsFailureOutcome() {
        Tool tool = Tool.builder("failing_tool").build();
        CallToolResult errorResult = new CallToolResult(
                List.of(new TextContent("Tool execution failed: invalid input")),
                true,
                null,
                Map.of()
        );

        CallToolResult actual = recorder.recordToolExecution(
                "session-err-result",
                "Run tool",
                "failing_tool",
                List.of(tool),
                () -> errorResult
        );

        assertThat(actual).isSameAs(errorResult);
        assertThat(actual.isError()).isTrue();
        verify(span).tag("decision.action", "failing_tool");
        verify(span).tag("decision.outcome.status", "FAILURE");
        verify(span).tag("decision.outcome.detail", "Tool execution failed: invalid input");
    }

    @Test
    @DisplayName("WO-014: buildEvaluatedAlternatives for direct response marks tools rejected and reply_to_user selected")
    void buildEvaluatedAlternatives_forDirectResponse_marksToolsRejected() {
        Tool tool1 = Tool.builder("tool_a").description("Tool A").build();
        Tool tool2 = Tool.builder("tool_b").description("Tool B").build();

        List<EvaluatedAlternative> alternatives = recorder.buildEvaluatedAlternatives(
                List.of(tool1, tool2), "reply_to_user", true);

        assertThat(alternatives).hasSize(3);

        EvaluatedAlternative alt1 = alternatives.get(0);
        assertThat(alt1.action()).isEqualTo("tool_a");
        assertThat(alt1.selected()).isFalse();
        assertThat(alt1.reasonRejected()).isEqualTo("Alternative tool not selected for current intent");

        EvaluatedAlternative alt2 = alternatives.get(1);
        assertThat(alt2.action()).isEqualTo("tool_b");
        assertThat(alt2.selected()).isFalse();
        assertThat(alt2.reasonRejected()).isEqualTo("Alternative tool not selected for current intent");

        EvaluatedAlternative directAlt = alternatives.get(2);
        assertThat(directAlt.action()).isEqualTo("reply_to_user");
        assertThat(directAlt.selected()).isTrue();
        assertThat(directAlt.reasonConsidered()).isEqualTo("Provide direct conversational answer to user");
        assertThat(directAlt.reasonRejected()).isNull();
    }

    @Test
    @DisplayName("WO-014: buildEvaluatedAlternatives marks selected tool and rejects alternatives")
    void buildEvaluatedAlternatives_marksSelectedAndRejectsAlternatives() {
        Tool tool1 = Tool.builder("tool_a").description("Tool A").build();
        Tool tool2 = Tool.builder("tool_b").description("Tool B").build();

        List<EvaluatedAlternative> alternatives = recorder.buildEvaluatedAlternatives(
                List.of(tool1, tool2), "tool_a", false);

        assertThat(alternatives).hasSize(2);

        EvaluatedAlternative alt1 = alternatives.get(0);
        assertThat(alt1.action()).isEqualTo("tool_a");
        assertThat(alt1.selected()).isTrue();
        assertThat(alt1.reasonRejected()).isNull();

        EvaluatedAlternative alt2 = alternatives.get(1);
        assertThat(alt2.action()).isEqualTo("tool_b");
        assertThat(alt2.selected()).isFalse();
        assertThat(alt2.reasonRejected()).isEqualTo("Alternative tool not selected for current intent");
    }

    @Test
    @DisplayName("WO-014: Fallback zero trace and span IDs when tracer is null")
    void recordDecision_withoutTracer_usesZeroTraceAndSpanIds() {
        AgentDecisionRecorder untracedRecorder = new AgentDecisionRecorder(objectMapper, (Tracer) null);

        DecisionEvent event = untracedRecorder.recordDirectResponseDecision(
                "session-untraced", "Hello", List.of());

        assertThat(event.traceId()).isEqualTo("00000000000000000000000000000000");
        assertThat(event.spanId()).isEqualTo("0000000000000000");
    }

    @Test
    @DisplayName("WO-014: ObjectProvider constructor extracts tracer")
    @SuppressWarnings("unchecked")
    void constructor_withObjectProvider_extractsTracer() {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);

        AgentDecisionRecorder recorderFromProvider = new AgentDecisionRecorder(objectMapper, provider);

        DecisionEvent event = recorderFromProvider.recordDirectResponseDecision(
                "session-op", "Hi", List.of());

        assertThat(event.traceId()).isEqualTo("0123456789abcdef0123456789abcdef");
    }
}
