package vn.danang.polaris.outbox.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.store.PendingByType;

/**
 * Outbox metrics (E3 design §7, O6 design): backlog and oldest-pending age read from the table when sampled, in total
 * and per {@code event_type}, and hand-off latency, outcome and delivery lag recorded by the relay. Exported by the
 * app's registry (OTLP). Names, units and labels are a contract: see
 * {@code docs/development/design/operational-observability/O6a-async-metrics-contract.md}.
 */
public class OutboxMetrics {

    public static final String BACKLOG = "polaris.outbox.backlog";
    public static final String OLDEST_PENDING_AGE = "polaris.outbox.oldest.pending.age";
    public static final String PENDING_BY_TYPE = "polaris.outbox.pending";
    public static final String PENDING_OLDEST_AGE_BY_TYPE = "polaris.outbox.pending.oldest.age";
    public static final String DELIVERY_LAG = "polaris.outbox.delivery.lag";
    public static final String HANDOFF = "polaris.outbox.handoff";
    public static final String PURGED = "polaris.outbox.purged";
    public static final String EVENT_TYPE = "event_type";

    /** One grouped query serves every per-type gauge read of a scrape. */
    static final Duration SNAPSHOT_TTL = Duration.ofSeconds(5);

    private final MeterRegistry registry;
    private final OutboxRelayStore store;
    private final Clock clock;
    private final Counter purged;
    private final Map<String, Boolean> typesWithGauges = new ConcurrentHashMap<>();

    private Instant snapshotAt = Instant.MIN;
    private Map<String, PendingByType> snapshot = Map.of();

    public OutboxMetrics(MeterRegistry registry, OutboxRelayStore store, Clock clock) {
        this.registry = registry;
        this.store = store;
        this.clock = clock;
        Gauge.builder(BACKLOG, store, s -> {
                    syncTypeGauges(); // per-type gauges appear when a type first has a pending event
                    return s.countPending();
                })
                .description("Recorded integration events not yet handed to a transport")
                .baseUnit("events")
                .strongReference(true)
                .register(registry);
        Gauge.builder(OLDEST_PENDING_AGE, store, s -> s.oldestPendingOccurredAt()
                        .map(oldest -> ageSeconds(oldest))
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
    public void recordHandOff(String destination, String eventType, boolean success, Duration duration) {
        Timer.builder(HANDOFF)
                .description("Transport hand-off attempts of outbox events")
                .tag("destination", destination)
                .tag(EVENT_TYPE, eventType)
                .tag("outcome", success ? "success" : "failure")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /** One delivered event: time from recording to accepted hand-off. */
    public void recordDelivered(String destination, String eventType, Duration lag) {
        Timer.builder(DELIVERY_LAG)
                .description("Time from recording an integration event to its accepted hand-off")
                .tag("destination", destination)
                .tag(EVENT_TYPE, eventType)
                .publishPercentileHistogram()
                .register(registry)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }

    public void recordPurged(int count) {
        purged.increment(count);
    }

    /** Registers a gauge pair for each event type seen pending so far; a drained type keeps reporting 0. */
    private void syncTypeGauges() {
        for (String type : currentSnapshot().keySet()) {
            typesWithGauges.computeIfAbsent(type, this::registerTypeGauges);
        }
    }

    private Boolean registerTypeGauges(String type) {
        Gauge.builder(PENDING_BY_TYPE, this, m -> {
                    PendingByType pending = m.currentSnapshot().get(type);
                    return pending == null ? 0.0 : pending.count();
                })
                .description("Pending integration events of one event type")
                .baseUnit("events")
                .tag(EVENT_TYPE, type)
                .strongReference(true)
                .register(registry);
        Gauge.builder(PENDING_OLDEST_AGE_BY_TYPE, this, m -> {
                    PendingByType pending = m.currentSnapshot().get(type);
                    return pending == null ? 0.0 : m.ageSeconds(pending.oldestOccurredAt());
                })
                .description("Age of the oldest pending integration event of one event type; 0 when none is pending")
                .baseUnit("seconds")
                .tag(EVENT_TYPE, type)
                .strongReference(true)
                .register(registry);
        return Boolean.TRUE;
    }

    private synchronized Map<String, PendingByType> currentSnapshot() {
        Instant now = clock.instant();
        if (Duration.between(snapshotAt, now).compareTo(SNAPSHOT_TTL) >= 0 || now.isBefore(snapshotAt)) {
            List<PendingByType> rows = store.pendingByType();
            snapshot = rows.stream().collect(Collectors.toMap(PendingByType::type, Function.identity()));
            snapshotAt = now;
        }
        return snapshot;
    }

    private double ageSeconds(Instant since) {
        return Math.max(0, Duration.between(since, clock.instant()).toMillis()) / 1000.0;
    }
}
