package vn.danang.polaris.assistant.web;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import vn.danang.polaris.assistant.resilience.AssistantUnavailableException;

/**
 * Maps {@link AssistantUnavailableException} to {@code 503 Service Unavailable} Problem Details with the fixed
 * "temporarily down" message and a {@code Retry-After} header. Ordered ahead of the shared
 * {@code GlobalExceptionHandler}, whose catch-all would otherwise turn it into a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AssistantUnavailableExceptionHandler {

    public static final String TYPE = "https://polaris.local/errors/assistant-unavailable";

    @ExceptionHandler(AssistantUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAssistantUnavailable(AssistantUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, AssistantUnavailableException.MESSAGE);
        problem.setTitle("Assistant Unavailable");
        problem.setType(URI.create(TYPE));
        long retryAfterSeconds = Math.max(1, ex.retryAfter().toSeconds());
        problem.setProperty("retry_after_seconds", retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(problem);
    }
}
