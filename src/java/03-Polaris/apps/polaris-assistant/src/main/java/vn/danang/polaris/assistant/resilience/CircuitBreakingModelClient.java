package vn.danang.polaris.assistant.resilience;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.GeminiAiModelClient;
import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Guards {@link GeminiAiModelClient} with the {@value CircuitBreakerConfiguration#GEMINI} circuit breaker.
 * <p>
 * Without the model nothing can be answered (not even the degraded order-status turn), so a failed Gemini call or
 * an open breaker ends the turn with {@link AssistantUnavailableException} instead of surfacing the provider's error
 * text as a reply. Responses where Gemini was not consulted (no API key: local echo) pass through and don't affect
 * the breaker.
 */
@Component
@Primary
public class CircuitBreakingModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingModelClient.class);

    private final AssistantModelClient delegate;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public CircuitBreakingModelClient(GeminiAiModelClient delegate, CircuitBreakerRegistry registry) {
        this((AssistantModelClient) delegate, registry.circuitBreaker(CircuitBreakerConfiguration.GEMINI));
    }

    CircuitBreakingModelClient(AssistantModelClient delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        return guarded(() -> delegate.generateResponse(messages, List.of())).text();
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        return guarded(() -> delegate.generateResponse(messages, tools));
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools, ModelRequestContext context) {
        return guarded(() -> delegate.generateResponse(messages, tools, context));
    }

    private ModelResponse guarded(Supplier<ModelResponse> call) {
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Gemini circuit breaker is {}; assistant unavailable", circuitBreaker.getState());
            throw unavailable(null);
        }

        long start = circuitBreaker.getCurrentTimestamp();
        ModelResponse response;
        try {
            response = call.get();
        } catch (RuntimeException e) {
            circuitBreaker.onError(elapsedSince(start), circuitBreaker.getTimestampUnit(), e);
            throw e;
        }

        ModelCall modelCall = response != null ? response.modelCall() : null;
        if (modelCall == null) {
            circuitBreaker.releasePermission();
            return response;
        }
        if (modelCall.isFailed()) {
            circuitBreaker.onError(elapsedSince(start), circuitBreaker.getTimestampUnit(), modelCall.failure());
            log.warn("Gemini call failed error={}; assistant unavailable", modelCall.failure().getClass().getSimpleName());
            throw unavailable(modelCall.failure());
        }
        circuitBreaker.onSuccess(elapsedSince(start), circuitBreaker.getTimestampUnit());
        return response;
    }

    private AssistantUnavailableException unavailable(Throwable cause) {
        // The open-state wait: by then the breaker lets trial calls through again.
        long waitMillis = circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
        return new AssistantUnavailableException(Duration.ofMillis(waitMillis), cause);
    }

    private long elapsedSince(long start) {
        return Math.max(0, circuitBreaker.getCurrentTimestamp() - start);
    }
}
