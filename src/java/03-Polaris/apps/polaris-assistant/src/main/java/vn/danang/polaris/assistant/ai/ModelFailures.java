package vn.danang.polaris.assistant.ai;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.OptionalInt;

import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.annotation.Nullable;

/**
 * Classifies model provider failures for the circuit breakers, the retry and the HTTP mapping. A
 * {@link ModelUnavailableException} is classified by its cause.
 */
public final class ModelFailures {

    private ModelFailures() {
    }

    /** The provider is failing: transport error, timeout, 429 or 5xx. Counts against its circuit breaker. */
    public static boolean isOutage(@Nullable Throwable failure) {
        Throwable cause = unwrap(failure);
        OptionalInt status = providerStatus(cause);
        if (status.isPresent()) {
            return isRateLimitOrServerError(status.getAsInt());
        }
        return isTransportError(cause);
    }

    /** Worth calling again: connection error, 429 or 5xx. A request timeout is not; it already used its time. */
    public static boolean isRetryable(@Nullable Throwable failure) {
        Throwable cause = unwrap(failure);
        OptionalInt status = providerStatus(cause);
        if (status.isPresent()) {
            return isRateLimitOrServerError(status.getAsInt());
        }
        if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
            return false;
        }
        return isTransportError(cause);
    }

    /** The provider rejected our request (4xx other than 429): our defect, not an outage. */
    public static boolean isRejectedRequest(@Nullable Throwable failure) {
        OptionalInt status = providerStatus(unwrap(failure));
        if (status.isEmpty()) {
            return false;
        }
        int code = status.getAsInt();
        return code >= 400 && code < 500 && code != 429;
    }

    private static boolean isRateLimitOrServerError(int status) {
        return status == 429 || status >= 500;
    }

    private static boolean isTransportError(@Nullable Throwable cause) {
        return cause instanceof IOException
                && !(cause instanceof TurnDeadlineExceededException)
                && !(cause instanceof JsonProcessingException);
    }

    private static OptionalInt providerStatus(@Nullable Throwable cause) {
        return cause instanceof ModelProviderException provider ? OptionalInt.of(provider.statusCode()) : OptionalInt.empty();
    }

    @Nullable
    private static Throwable unwrap(@Nullable Throwable failure) {
        return failure instanceof ModelUnavailableException unavailable ? unavailable.getCause() : failure;
    }
}
