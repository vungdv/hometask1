package vn.danang.polaris.assistant.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * One {@link CircuitBreakerRegistry} for the assistant's model providers. Breaker state, call outcomes and
 * not-permitted calls are exported as {@code resilience4j.circuitbreaker.*} meters tagged {@code name}.
 */
@Configuration
public class CircuitBreakerConfiguration {

    public static final String TYPESAFE = "typesafe";
    public static final String GEMINI = "gemini";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(AssistantResilienceProperties properties, MeterRegistry meterRegistry) {
        AssistantResilienceProperties.CircuitBreaker settings = properties.getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.getSlidingWindowSize())
                .minimumNumberOfCalls(settings.getMinimumNumberOfCalls())
                .failureRateThreshold(settings.getFailureRateThreshold())
                .waitDurationInOpenState(settings.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        registry.circuitBreaker(TYPESAFE);
        registry.circuitBreaker(GEMINI);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }
}
