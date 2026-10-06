package vn.danang.polaris.assistant.resilience;

import java.time.Duration;
import java.time.Instant;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Nullable;

/**
 * Watches one circuit breaker: tags its state on the active trace span and knows when an open breaker will
 * let trial calls through again.
 */
final class CircuitBreakerObserver {

    private final CircuitBreaker circuitBreaker;
    @Nullable
    private final Tracer tracer;
    private volatile Instant halfOpenAt = Instant.EPOCH;

    CircuitBreakerObserver(CircuitBreaker circuitBreaker, @Nullable Tracer tracer) {
        this.circuitBreaker = circuitBreaker;
        this.tracer = tracer;
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                long waitMillis = circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1);
                halfOpenAt = event.getCreationTime().toInstant().plusMillis(waitMillis);
            }
        });
    }

    /** Time until the open breaker half-opens; zero when that is already due or unknown. */
    Duration untilHalfOpen() {
        Duration remaining = Duration.between(Instant.now(), halfOpenAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** Tags {@code circuit_breaker.<name>.state} on the active span. */
    void tagState() {
        Span span = tracer != null ? tracer.currentSpan() : null;
        if (span != null) {
            span.tag("circuit_breaker." + circuitBreaker.getName() + ".state", circuitBreaker.getState().name());
        }
    }
}
