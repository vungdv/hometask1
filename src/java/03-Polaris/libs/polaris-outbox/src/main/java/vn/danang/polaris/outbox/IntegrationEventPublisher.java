package vn.danang.polaris.outbox;

import java.util.UUID;

/**
 * Broker-agnostic publish port (TR-E5). Business code depends on this interface only.
 *
 * <p>Publishing records the event atomically with the caller's business change: it is delivered
 * if and only if the surrounding transaction commits (TR-E1). Delivery itself is asynchronous and
 * at-least-once, with the returned id stable across retries (TR-E4).
 */
public interface IntegrationEventPublisher {

    /**
     * Records {@code event} in the current transaction.
     *
     * @return the event's CloudEvents {@code id} ({@code ce_id}), assigned now
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    UUID publish(IntegrationEvent event);
}
