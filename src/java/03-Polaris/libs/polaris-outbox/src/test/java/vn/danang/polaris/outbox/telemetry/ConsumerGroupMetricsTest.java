package vn.danang.polaris.outbox.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ConsumerGroupMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ConsumerGroupMetrics metrics = new ConsumerGroupMetrics(registry, Clock.fixed(NOW, ZoneOffset.UTC));
    private final MockConsumer<String, String> consumer = new MockConsumer<>(AutoOffsetResetStrategy.EARLIEST.name());

    private static ConsumerRecord<String, String> record(String topic, int partition, Instant timestamp) {
        return new ConsumerRecord<>(topic, partition, 0L, timestamp.toEpochMilli(),
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "k", "v",
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
    }

    private double age(String group) {
        return registry.get(ConsumerGroupMetrics.OLDEST_RECORD_AGE).tag("group", group).gauge().value();
    }

    @Test
    void idleGroup_reportsZero() {
        metrics.<String, String>recordInterceptor("g1");
        assertThat(age("g1")).isZero();
    }

    @Test
    void inFlightRecord_setsAge_thenSuccessClearsIt_andTheOldestPartitionWins() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        var older = record("t", 0, NOW.minusSeconds(90));
        var newer = record("t", 1, NOW.minusSeconds(5));
        tracker.intercept(older, consumer);
        tracker.intercept(newer, consumer);
        assertThat(age("g1")).isEqualTo(90);

        tracker.success(older, consumer);
        assertThat(age("g1")).isEqualTo(5);
        tracker.success(newer, consumer);
        assertThat(age("g1")).isZero();
    }

    @Test
    void aFailingRecord_staysInFlight_untilItIsSkipped_whichIsCounted() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        var poison = record("t", 0, NOW.minusSeconds(60));
        tracker.intercept(poison, consumer);
        tracker.failure(poison, new IllegalStateException("boom"), consumer);
        assertThat(age("g1")).as("a failing record still blocks its partition").isEqualTo(60);

        tracker.skipped(poison);
        assertThat(age("g1")).isZero();
        assertThat(registry.get(ConsumerGroupMetrics.RECORDS_SKIPPED).tag("group", "g1").tag("topic", "t").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void groupsAreIndependent() {
        var a = metrics.<String, String>recordInterceptor("a");
        metrics.<String, String>recordInterceptor("b");
        a.intercept(record("t", 0, NOW.minusSeconds(30)), consumer);
        assertThat(age("a")).isEqualTo(30);
        assertThat(age("b")).isZero();
    }
}
