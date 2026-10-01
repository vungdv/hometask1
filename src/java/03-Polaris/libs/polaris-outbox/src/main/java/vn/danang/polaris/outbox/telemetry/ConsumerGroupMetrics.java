package vn.danang.polaris.outbox.telemetry;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.RecordInterceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Per consumer-group Kafka metrics (O6 design §3). Two parts, both labelled {@code group}:
 * <ul>
 * <li>{@link #clientMetrics(MeterRegistry, String)}: the Kafka client's own consumer metrics (notably
 * {@code kafka.consumer.fetch.manager.records.lag} per topic and partition), tagged with the group so lag is
 * addressable by group instead of by a client id.</li>
 * <li>{@link #recordInterceptor(String)}: {@code polaris.kafka.consumer.oldest.record.age}, the age of the oldest
 * record the group's consumer has taken but not finished (Kafka clients expose no record age), and
 * {@code polaris.kafka.consumer.records.skipped}, records given up on after retries.</li>
 * </ul>
 * One instance serves one registry; call {@link #recordInterceptor(String)} once per group.
 */
public class ConsumerGroupMetrics {

    public static final String OLDEST_RECORD_AGE = "polaris.kafka.consumer.oldest.record.age";
    public static final String RECORDS_SKIPPED = "polaris.kafka.consumer.records.skipped";
    public static final String GROUP = "group";

    private final MeterRegistry registry;
    private final Clock clock;

    public ConsumerGroupMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /** Client metrics of every consumer created by the factory this listener is added to, tagged with the group. */
    public static <K, V> MicrometerConsumerListener<K, V> clientMetrics(MeterRegistry registry, String group) {
        return new MicrometerConsumerListener<>(registry, List.of(new ImmutableTag(GROUP, group)));
    }

    public <K, V> GroupTracker<K, V> recordInterceptor(String group) {
        return new GroupTracker<>(group);
    }

    /**
     * Tracks in-flight records of one group. A record is in flight from the moment the container hands it to the
     * listener until it succeeds or is skipped; a failing record stays in flight while it is retried, so a poison
     * record that blocks a partition makes the age grow.
     */
    public final class GroupTracker<K, V> implements RecordInterceptor<K, V> {

        private final String group;
        private final Map<TopicPartition, Long> inFlightTimestamps = new ConcurrentHashMap<>();

        private GroupTracker(String group) {
            this.group = group;
            Gauge.builder(OLDEST_RECORD_AGE, this, GroupTracker::oldestAgeSeconds)
                    .description("Age of the oldest record taken by the consumer group and not yet processed; 0 when idle")
                    .baseUnit("seconds")
                    .tag(GROUP, group)
                    .strongReference(true)
                    .register(registry);
        }

        @Override
        public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record,
                Consumer<K, V> consumer) {
            inFlightTimestamps.put(new TopicPartition(record.topic(), record.partition()), record.timestamp());
            return record;
        }

        @Override
        public void success(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
            inFlightTimestamps.remove(new TopicPartition(record.topic(), record.partition()));
        }

        /** The error handler gave up on this record and moved on: it no longer blocks the partition. */
        public void skipped(ConsumerRecord<?, ?> record) {
            inFlightTimestamps.remove(new TopicPartition(record.topic(), record.partition()));
            Counter.builder(RECORDS_SKIPPED)
                    .description("Records skipped by the consumer group after exhausting retries")
                    .baseUnit("records")
                    .tag(GROUP, group)
                    .tag("topic", record.topic())
                    .register(registry)
                    .increment();
        }

        double oldestAgeSeconds() {
            long now = clock.millis();
            return inFlightTimestamps.values().stream()
                    .mapToLong(timestamp -> Math.max(0, now - timestamp))
                    .max()
                    .orElse(0) / 1000.0;
        }
    }
}
