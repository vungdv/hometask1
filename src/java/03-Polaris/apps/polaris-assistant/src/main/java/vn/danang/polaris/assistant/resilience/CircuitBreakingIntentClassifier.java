package vn.danang.polaris.assistant.resilience;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentClassifier;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.TypeSafeIntentClassifier;

/**
 * Guards {@link TypeSafeIntentClassifier} with the {@value CircuitBreakerConfiguration#TYPESAFE} circuit breaker.
 * <p>
 * A failed TypeSafe call (transport error, non-2xx) counts as a breaker failure. While TypeSafe is failing or the
 * breaker is open, the turn is not classified: it is pinned to the configured degraded intent (order status by
 * default) with a notice that the assistant can only help with that for now. Results where TypeSafe was not
 * consulted (missing API key, empty taxonomy) pass through untouched and don't affect the breaker.
 */
@Component
@Primary
public class CircuitBreakingIntentClassifier implements IntentClassifier {

    public static final String DEGRADED_NOTICE =
            "Polaris Assistant is having a problem right now, so I can only help with checking your order status.";
    static final String REASON_CIRCUIT_OPEN = "circuit_open";

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingIntentClassifier.class);

    private final IntentClassifier delegate;
    private final CircuitBreaker circuitBreaker;
    private final String degradedIntent;

    @Autowired
    public CircuitBreakingIntentClassifier(TypeSafeIntentClassifier delegate, CircuitBreakerRegistry registry,
            AssistantResilienceProperties properties) {
        this((IntentClassifier) delegate, registry.circuitBreaker(CircuitBreakerConfiguration.TYPESAFE), properties.getDegradedIntent());
    }

    CircuitBreakingIntentClassifier(IntentClassifier delegate, CircuitBreaker circuitBreaker, String degradedIntent) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.degradedIntent = degradedIntent;
    }

    @Override
    public IntentClassification classify(String query, List<AssistantMessage> history, Collection<IntentDefinition> intents) {
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("TypeSafe circuit breaker is {}; pinning turn to degraded intent={}", circuitBreaker.getState(), degradedIntent);
            return IntentClassification.degraded(degradedIntent, REASON_CIRCUIT_OPEN, DEGRADED_NOTICE);
        }

        long start = circuitBreaker.getCurrentTimestamp();
        IntentClassification result;
        try {
            result = delegate.classify(query, history, intents);
        } catch (RuntimeException e) {
            circuitBreaker.onError(elapsedSince(start), circuitBreaker.getTimestampUnit(), e);
            log.warn("TypeSafe classification threw; pinning turn to degraded intent={} error={}", degradedIntent, e.getClass().getSimpleName());
            return IntentClassification.degraded(degradedIntent, "unexpected_error", DEGRADED_NOTICE);
        }

        ModelCall call = result.modelCall();
        if (call == null) {
            circuitBreaker.releasePermission();
            return result;
        }
        if (call.isFailed()) {
            circuitBreaker.onError(elapsedSince(start), circuitBreaker.getTimestampUnit(), call.failure());
            log.warn("TypeSafe classification failed reason={}; pinning turn to degraded intent={}", result.fallbackReason(), degradedIntent);
            return IntentClassification.degraded(degradedIntent, result.fallbackReason(), DEGRADED_NOTICE).withModelCall(call);
        }
        circuitBreaker.onSuccess(elapsedSince(start), circuitBreaker.getTimestampUnit());
        return result;
    }

    private long elapsedSince(long start) {
        return Math.max(0, circuitBreaker.getCurrentTimestamp() - start);
    }
}
