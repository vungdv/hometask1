package vn.danang.polaris.assistant.resilience;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.ai.ModelProviderException;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ModelTokenUsage;

/**
 * Unit tests for {@link CircuitBreakingModelClient}: Gemini is mocked at its {@link AssistantModelClient} seam;
 * the breaker is a real Resilience4j instance.
 */
class CircuitBreakingModelClientTest {

    private AssistantModelClient gemini;
    private CircuitBreaker breaker;
    private CircuitBreakingModelClient client;

    @BeforeEach
    void setUp() {
        gemini = mock(AssistantModelClient.class);
        breaker = CircuitBreaker.of("gemini", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(45))
                .build());
        client = new CircuitBreakingModelClient(gemini, breaker);
    }

    @Test
    @DisplayName("Given Gemini answers, then the response passes through and the breaker records a success")
    void passes_through_successful_response() {
        ModelResponse ok = new ModelResponse("hello").withModelCall(ModelCall.succeeded("gemini", null, null, ModelTokenUsage.NONE, "stop"));
        when(gemini.generateResponse(any(), any(), any())).thenReturn(ok);

        assertThat(client.generateResponse(List.of(), List.of(), ModelRequestContext.empty())).isSameAs(ok);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given a failed Gemini call, then AssistantUnavailableException with the open-state wait as Retry-After")
    void throws_unavailable_when_gemini_fails() {
        when(gemini.generateResponse(any(), any(), any())).thenReturn(failed());

        assertThatThrownBy(() -> client.generateResponse(List.of(), List.of(), ModelRequestContext.empty()))
                .isInstanceOfSatisfying(AssistantUnavailableException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(AssistantUnavailableException.MESSAGE);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(e.getCause()).isInstanceOf(ModelProviderException.class);
                });
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given repeated failures open the breaker, then Gemini is no longer called")
    void short_circuits_when_open() {
        when(gemini.generateResponse(any(), any(), any())).thenReturn(failed());
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.generateResponse(List.of(), List.of(), ModelRequestContext.empty()))
                    .isInstanceOf(AssistantUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> client.generateResponse(List.of(), List.of(), ModelRequestContext.empty()))
                .isInstanceOf(AssistantUnavailableException.class)
                .hasNoCause();
        verify(gemini, times(2)).generateResponse(any(), any(), any());
    }

    @Test
    @DisplayName("Given a local reply without a provider call (no API key), then it passes through and the breaker is untouched")
    void ignores_responses_without_a_model_call() {
        ModelResponse echo = new ModelResponse("I am Polaris Assistant! (echo)");
        when(gemini.generateResponse(any(), any(), any())).thenReturn(echo);

        assertThat(client.generateResponse(List.of(), List.of(), ModelRequestContext.empty())).isSameAs(echo);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("Given the delegate throws, then the failure is recorded and the original exception propagates")
    void records_and_rethrows_delegate_exceptions() {
        when(gemini.generateResponse(any(), any(), any())).thenThrow(new IllegalArgumentException("no key"));

        assertThatThrownBy(() -> client.generateResponse(List.of(), List.of(), ModelRequestContext.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private static ModelResponse failed() {
        return new ModelResponse("Unable to get response from AI Model (503).")
                .withModelCall(ModelCall.failed("gemini", new ModelProviderException(503)));
    }
}
