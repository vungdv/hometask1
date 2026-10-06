package vn.danang.polaris.assistant.web;

import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import vn.danang.polaris.assistant.ai.ModelUnavailableException;

/**
 * Maps {@link ModelUnavailableException} to {@code 503 Service Unavailable} as RFC 7807 Problem Details, with
 * {@code Retry-After} (RFC 9110 §10.2.3, delay-seconds) when the model provider gave one.
 * <p>
 * The provider's error text is never put in the response: it may hold internal details and is not actionable
 * for the user. Ordered ahead of the shared {@code GlobalExceptionHandler}, whose catch-all would answer 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ModelUnavailableExceptionHandler {

    static final String TYPE = "https://polaris.local/errors/assistant-unavailable";

    private static final Logger log = LoggerFactory.getLogger(ModelUnavailableExceptionHandler.class);

    @ExceptionHandler(ModelUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleModelUnavailable(ModelUnavailableException ex) {
        log.warn("AI model unavailable, answering 503: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The assistant is temporarily unavailable. Please try again shortly.");
        problem.setTitle("Assistant Temporarily Unavailable");
        problem.setType(URI.create(TYPE));
        problem.setProperty("remedy", "Retry the message later. Anything the assistant already did in this turn "
                + "(for example a staged order draft) has been kept.");

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        ex.retryAfter().ifPresent(delay -> response.header(HttpHeaders.RETRY_AFTER, String.valueOf(toSeconds(delay))));
        return response.body(problem);
    }

    /** Retry-After delay-seconds: whole seconds, rounded up, at least 1. */
    private static long toSeconds(Duration delay) {
        long seconds = delay.toSeconds() + (delay.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
