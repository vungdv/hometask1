package vn.danang.polaris.assistant.observability;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;

class DecisionEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // =========================================================================
    // 1. Happy path — attribute recording and JSON serialization
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid DecisionEvent with Outcome, when recordOn is called, then attaches all semantic tags to active span")
        void records_attributes_on_active_span() {
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
        @DisplayName("Given DecisionEvent with outcome added, when serialized to JSON, then emits snake_case fields including outcome")
        void records_outcome_and_serializes_snake_case_json() throws Exception {
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

            Outcome outcome = new Outcome("SUCCESS", "Deals found", 50L);
            DecisionEvent completedEvent = startEvent.withOutcome(outcome);

            String json = objectMapper.writeValueAsString(completedEvent);

            assertThat(json).contains("\"trace_id\":\"trace-abc\"");
            assertThat(json).contains("\"span_id\":\"span-def\"");
            assertThat(json).contains("\"agent_id\":\"polaris-assistant\"");
            assertThat(json).contains("\"session_id\":\"session-xyz\"");
            assertThat(json).contains("\"selected_action\":\"promotions\"");
            assertThat(json).contains("\"policy_constraint\":\"MAX_TOOL_ITERATIONS=5\"");
            assertThat(json).contains("\"outcome\":{");
            assertThat(json).contains("\"status\":\"SUCCESS\"");
            assertThat(json).contains("\"detail\":\"Deals found\"");
            assertThat(json).contains("\"latency_ms\":50");
        }
    }

    // =========================================================================
    // 2. Invalid input & null safety
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & null safety")
    class InvalidInput {

        @Test
        @DisplayName("Given null span, when recordOn is called, then completes safely without throwing NPE")
        void handles_null_span_gracefully_without_throwing() {
            DecisionEvent event = new DecisionEvent(
                    "trace-1", "span-1", "agent-1", "sess-1", Instant.now(),
                    "intent", List.of(), "action", 1.0, "policy", null
            );

            assertThatCode(() -> event.recordOn(null)).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // 3. Edge cases — start event serialization without outcome
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given in-flight DecisionEvent without outcome, when serialized to JSON, then omits outcome node")
        void omits_outcome_field_when_not_yet_completed() throws Exception {
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

            String startJson = objectMapper.writeValueAsString(startEvent);

            assertThat(startJson).doesNotContain("\"outcome\"");
        }
    }
}
