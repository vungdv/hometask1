package vn.danang.polaris.assistant.observability;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;

class DecisionEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("WO-014: recordOn attaches semantic decision attributes to span")
    void starting_and_recordOn_attachesAttributesToSpan() {
        Span span = mock(Span.class);

        EvaluatedAlternative alt1 = new EvaluatedAlternative("search_products", "Catalog search", true, null);
        EvaluatedAlternative alt2 = new EvaluatedAlternative("order_lookup", "Order search", false, "Not selected");
        Outcome outcome = new Outcome("SUCCESS", "Found 3 items", 25L);

        DecisionEvent event = new DecisionEvent(
                "trace-123",
                "span-456",
                "polaris-assistant",
                "session-789",
                Instant.now(),
                "Find chargers",
                List.of(alt1, alt2),
                "search_products",
                0.95,
                "MAX_TOOL_ITERATIONS=5",
                outcome
        );

        event.recordOn(span);

        verify(span).tag("decision.action", "search_products");
        verify(span).tag("decision.intent", "Find chargers");
        verify(span).tag("decision.confidence", "0.95");
        verify(span).tag("decision.policy", "MAX_TOOL_ITERATIONS=5");
        verify(span).tag("decision.outcome.status", "SUCCESS");
        verify(span).tag("decision.outcome.detail", "Found 3 items");
        verify(span).tag("decision.outcome.latency_ms", "25");
    }

    @Test
    @DisplayName("WO-014: recordOn handles null span gracefully without throwing")
    void recordOn_withNullSpan_handlesGracefully() {
        DecisionEvent event = new DecisionEvent(
                "trace-1", "span-1", "agent-1", "sess-1", Instant.now(),
                "intent", List.of(), "action", 1.0, "policy", null
        );
        event.recordOn(null); // Must not throw
    }

    @Test
    @DisplayName("WO-014: withOutcome records outcome and generates snake_case JSON fields")
    void withOutcome_recordsOutcomeAndGeneratesLogFields() throws Exception {
        DecisionEvent startEvent = new DecisionEvent(
                "trace-abc",
                "span-def",
                "polaris-assistant",
                "session-xyz",
                Instant.parse("2026-09-16T10:00:00Z"),
                "Show deals",
                List.of(new EvaluatedAlternative("promotions", "Promo tool", true, null)),
                "promotions",
                1.0,
                "MAX_TOOL_ITERATIONS=5",
                null
        );

        // Verify start event omits outcome
        String startJson = objectMapper.writeValueAsString(startEvent);
        assertThat(startJson).contains("\"trace_id\":\"trace-abc\"");
        assertThat(startJson).contains("\"span_id\":\"span-def\"");
        assertThat(startJson).contains("\"agent_id\":\"polaris-assistant\"");
        assertThat(startJson).contains("\"session_id\":\"session-xyz\"");
        assertThat(startJson).contains("\"selected_action\":\"promotions\"");
        assertThat(startJson).contains("\"policy_constraint\":\"MAX_TOOL_ITERATIONS=5\"");
        assertThat(startJson).doesNotContain("\"outcome\"");

        // Add outcome
        Outcome outcome = new Outcome("SUCCESS", "Deals found", 50L);
        DecisionEvent completedEvent = startEvent.withOutcome(outcome);

        String completedJson = objectMapper.writeValueAsString(completedEvent);
        assertThat(completedJson).contains("\"outcome\":{");
        assertThat(completedJson).contains("\"status\":\"SUCCESS\"");
        assertThat(completedJson).contains("\"detail\":\"Deals found\"");
        assertThat(completedJson).contains("\"latency_ms\":50");
    }
}
