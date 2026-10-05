package vn.danang.polaris.assistant.resilience;

import java.time.Duration;

/**
 * The assistant cannot answer at all: the model provider is down or its circuit breaker is open. Mapped to
 * {@code 503 Service Unavailable} with a fixed message and a {@code Retry-After} hint.
 */
public class AssistantUnavailableException extends RuntimeException {

    public static final String MESSAGE = "Polaris Assistant is temporarily down. Please come back later.";

    private final Duration retryAfter;

    public AssistantUnavailableException(Duration retryAfter, Throwable cause) {
        super(MESSAGE, cause);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
