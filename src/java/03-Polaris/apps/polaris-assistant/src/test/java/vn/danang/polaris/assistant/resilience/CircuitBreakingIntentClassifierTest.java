package vn.danang.polaris.assistant.resilience;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.ai.ModelTokenUsage;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentClassifier;
import vn.danang.polaris.assistant.intent.IntentDefinition;

/**
 * Unit tests for {@link CircuitBreakingIntentClassifier}: the TypeSafe classifier is mocked at its
 * {@link IntentClassifier} seam; the breaker is a real Resilience4j instance.
 */
class CircuitBreakingIntentClassifierTest {

    private static final String DEGRADED_INTENT = "information.lookup.order.status";
    private static final List<IntentDefinition> INTENTS = List.of(new IntentDefinition("catalog.product.search", "search", List.of()));

    private IntentClassifier typeSafe;
    private CircuitBreaker breaker;
    private CircuitBreakingIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        typeSafe = mock(IntentClassifier.class);
        breaker = CircuitBreaker.of("typesafe", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        classifier = new CircuitBreakingIntentClassifier(typeSafe, breaker, DEGRADED_INTENT);
    }

    @Test
    @DisplayName("Given TypeSafe answers, then its classification passes through and the breaker records a success")
    void passes_through_successful_classification() {
        IntentClassification ok = new IntentClassification("catalog.product.search", 0.93)
                .withModelCall(ModelCall.succeeded("jev", null, null, ModelTokenUsage.NONE, "stop"));
        when(typeSafe.classify(any(), any(), any())).thenReturn(ok);

        IntentClassification result = classifier.classify("find chargers", List.of(), INTENTS);

        assertThat(result).isSameAs(ok);
        assertThat(result.isDegraded()).isFalse();
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given a failed TypeSafe call, then the turn is pinned to order status with the notice")
    void pins_order_status_when_typesafe_call_fails() {
        when(typeSafe.classify(any(), any(), any())).thenReturn(failed());

        IntentClassification result = classifier.classify("find chargers", List.of(), INTENTS);

        assertThat(result.intentId()).isEqualTo(DEGRADED_INTENT);
        assertThat(result.isDegraded()).isTrue();
        assertThat(result.degradedNotice()).isEqualTo(CircuitBreakingIntentClassifier.DEGRADED_NOTICE);
        assertThat(result.fallbackReason()).isEqualTo("io_error");
        assertThat(result.modelCall().isFailed()).isTrue();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given repeated failures open the breaker, then TypeSafe is no longer called and the turn is pinned")
    void short_circuits_when_open() {
        when(typeSafe.classify(any(), any(), any())).thenReturn(failed());
        classifier.classify("a", List.of(), INTENTS);
        classifier.classify("b", List.of(), INTENTS);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        IntentClassification result = classifier.classify("c", List.of(), INTENTS);

        verify(typeSafe, times(2)).classify(any(), any(), any());
        assertThat(result.intentId()).isEqualTo(DEGRADED_INTENT);
        assertThat(result.fallbackReason()).isEqualTo(CircuitBreakingIntentClassifier.REASON_CIRCUIT_OPEN);
        assertThat(result.modelCall()).isNull();
    }

    @Test
    @DisplayName("Given TypeSafe was not consulted (no API key), then the fallback passes through and the breaker is untouched")
    void ignores_results_without_a_model_call() {
        IntentClassification noKey = IntentClassification.fallback("general.conversation", "missing_api_key");
        when(typeSafe.classify(any(), any(), any())).thenReturn(noKey);

        IntentClassification result = classifier.classify("hi", List.of(), INTENTS);

        assertThat(result).isSameAs(noKey);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("Given the classifier throws, then the turn is pinned and the failure is recorded")
    void pins_order_status_when_classifier_throws() {
        when(typeSafe.classify(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        IntentClassification result = classifier.classify("hi", List.of(), INTENTS);

        assertThat(result.intentId()).isEqualTo(DEGRADED_INTENT);
        assertThat(result.isDegraded()).isTrue();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private static IntentClassification failed() {
        return IntentClassification.fallback("general.conversation", "io_error")
                .withModelCall(ModelCall.failed("jev", new IOException("connection refused")));
    }
}
