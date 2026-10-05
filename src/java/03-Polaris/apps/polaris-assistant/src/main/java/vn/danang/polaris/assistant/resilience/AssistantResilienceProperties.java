package vn.danang.polaris.assistant.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Circuit breaker settings shared by the TypeSafe and Gemini breakers, plus the degraded-mode intent used while
 * TypeSafe is unavailable.
 */
@Configuration
@ConfigurationProperties(prefix = "polaris.assistant.resilience")
@Getter
@Setter
public class AssistantResilienceProperties {

    /** Intent every turn is pinned to while the TypeSafe breaker is open. */
    private String degradedIntent = "information.lookup.order.status";

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Getter
    @Setter
    public static class CircuitBreaker {
        /** Failure rate (percent) over the sliding window that opens the breaker. */
        private float failureRateThreshold = 50;
        /** Number of most recent calls the failure rate is computed over. */
        private int slidingWindowSize = 10;
        /** Calls needed in the window before the failure rate is evaluated. */
        private int minimumNumberOfCalls = 4;
        /** How long the breaker stays open before letting trial calls through (also the Retry-After hint). */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        /** Trial calls allowed while half-open. */
        private int permittedNumberOfCallsInHalfOpenState = 2;
    }
}
