package vn.danang.polaris.outbox.telemetry;

import java.time.Clock;
import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import vn.danang.polaris.outbox.store.OutboxRelayStore;

/**
 * Outbox metrics (E3 design §7): backlog and oldest-pending age read from the table when sampled, and
 * hand-off latency, outcome and delivery lag recorded by the relay. Exported by the app's registry (OTLP).
 */
public class OutboxMetrics {

    public static final String BACKLOG = "polaris.outbox.backlog";
    public static final String OLDEST_PENDING_AGE = "polaris.outbox.oldest.pending.age";
    public static final String DELIVERY_LAG = "polaris.outbox.delivery.lag";
    public static final String HANDOFF = "polaris.outbox.handoff";
    public static final String PURGED = "polaris.outbox.purged";

    private final MeterRegistry registry;
    private final Counter purged;

    public OutboxMetrics(MeterRegistry registry, OutboxRelayStore store, Clock clock) {
        this.registry = registry;
        Gauge.builder(BACKLOG, store, OutboxRelayStore::countPending)
                .description("Recorded integration events not yet handed to a transport")
                .baseUnit("events")
                .strongReference(true)
                .register(registry);
        Gauge.builder(OLDEST_PENDING_AGE, store, s -> s.oldestPendingOccurredAt()
                        .map(oldest -> Math.max(0, Duration.between(oldest, clock.instant()).toMillis()) / 1000.0)
                        .orElse(0.0))
                .description("Age of the oldest pending integration event; 0 when none is pending")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
        this.purged = Counter.builder(PURGED)
                .description("Delivered integration events removed by the retention purge")
                .baseUnit("events")
                .register(registry);
    }

    /** One transport invocation: latency and outcome. */
    public void recordHandOff(String destination, boolean success, Duration duration) {
        Timer.builder(HANDOFF)
                .description("Transport hand-off attempts of outbox events")
                .tag("destination", destination)
                .tag("outcome", success ? "success" : "failure")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /** One delivered event: time from recording to accepted hand-off. */
    public void recordDelivered(String destination, Duration lag) {
        Timer.builder(DELIVERY_LAG)
                .description("Time from recording an integration event to its accepted hand-off")
                .tag("destination", destination)
                .publishPercentileHistogram()
                .register(registry)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }

    public void recordPurged(int count) {
        purged.increment(count);
    }
}
