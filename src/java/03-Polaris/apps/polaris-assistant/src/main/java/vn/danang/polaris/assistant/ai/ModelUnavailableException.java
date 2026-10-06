package vn.danang.polaris.assistant.ai;

import java.time.Duration;
import java.util.Optional;

import jakarta.annotation.Nullable;

/**
 * The model provider could not produce a reply: it was unreachable, timed out, or answered with a non-2xx status.
 * Raised instead of returning error text as a reply, so callers fail loudly (HTTP 503) and never persist or show
 * provider error details to the user.
 * <p>
 * Carries the failed {@link ModelCall} (null when the provider was never reached, e.g. missing configuration) so
 * GenAI telemetry still records the failed generation, and the provider's {@code Retry-After} hint when it sent one.
 */
public class ModelUnavailableException extends RuntimeException implements ModelCallResult {

    @Nullable
    private final transient ModelCall modelCall;
    @Nullable
    private final Duration retryAfter;

    public ModelUnavailableException(String message, @Nullable Throwable cause, @Nullable ModelCall modelCall,
            @Nullable Duration retryAfter) {
        super(message, cause);
        this.modelCall = modelCall;
        this.retryAfter = retryAfter;
    }

    @Override
    @Nullable
    public ModelCall modelCall() {
        return modelCall;
    }

    /** How long the provider asked us to wait before calling again, when it said so. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
