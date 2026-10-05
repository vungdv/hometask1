package vn.danang.polaris.order.shipment;

import java.time.Clock;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

import io.cloudevents.rw.CloudEventRWException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.outbox.telemetry.ConsumerGroupMetrics;
import vn.danang.polaris.order.service.ShipmentProgressService;

/**
 * Wires Order's shipment-report listener: one container in consumer group {@code order.shipments}, trace context
 * continued from the record's {@code traceparent}. A malformed record is logged at ERROR with its coordinates and
 * skipped at once; any other failure is retried with exponential backoff first. Either way a poison record never
 * blocks the partition. {@code auto.offset.reset=earliest}: a first start replays the topic, which the
 * status guard makes harmless.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShipmentListenerProperties.class)
@ConditionalOnProperty(prefix = "polaris.order.shipment-listener", name = "enabled", havingValue = "true", matchIfMissing = true)
class ShipmentListenerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ShipmentListenerConfiguration.class);

    @Bean
    ShipmentReportHandler shipmentReportHandler(ShipmentProgressService service, JsonMapper mapper, MeterRegistry meters) {
        return new ShipmentReportHandler(service, mapper, meters);
    }

    @Bean
    ConsumerGroupMetrics consumerGroupMetrics(MeterRegistry meters) {
        return new ConsumerGroupMetrics(meters, Clock.systemUTC());
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> shipmentReportListener(ConsumerFactory<?, ?> applicationConsumerFactory,
            ShipmentListenerProperties config, ShipmentReportHandler handler, ObservationRegistry observationRegistry,
            ConsumerGroupMetrics groupMetrics, MeterRegistry meters) {
        var consumerFactory = new DefaultKafkaConsumerFactory<>(applicationConsumerFactory.getConfigurationProperties(),
                new StringDeserializer(), new ByteArrayDeserializer());
        // Consumer-group metrics: client lag metrics tagged with the group, and the oldest in-flight record age per group.
        consumerFactory.addListener(ConsumerGroupMetrics.clientMetrics(meters, config.groupId()));
        var tracker = groupMetrics.<String, byte[]>recordInterceptor(config.groupId());
        var properties = new ContainerProperties(config.topic());
        properties.setGroupId(config.groupId());
        properties.setConsumerRebalanceListener(tracker.rebalanceListener()); // revoked partitions drop in-flight state
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setObservationEnabled(true);
        properties.setObservationRegistry(observationRegistry);
        var overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.setKafkaConsumerProperties(overrides);
        properties.setMessageListener((MessageListener<String, byte[]>) handler::handle);
        var container = new ConcurrentMessageListenerContainer<>(consumerFactory, properties);
        container.setBeanName("order-shipment-reports");
        container.setRecordInterceptor(tracker);
        var backOff = new ExponentialBackOffWithMaxRetries(config.retries());
        backOff.setInitialInterval(config.backoffInitial().toMillis());
        backOff.setMultiplier(2.0);
        container.setCommonErrorHandler(errorHandler((record, e) -> {
            tracker.skipped(record);
            log.error("Shipment report skipped topic={} partition={} offset={} key={} error={}",
                    record.topic(), record.partition(), record.offset(), record.key(), e.toString());
        }, backOff));
        return container;
    }

    /**
     * Classifies failures (messaging-stability S1). A malformed or invalid record fails the same way on every attempt,
     * so it is recovered at once instead of after the backoff. A transient database failure (lock timeout, deadlock,
     * lost connection) is retried with the backoff. Anything unclassified keeps the default: retried.
     */
    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer, BackOff backOff) {
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(CloudEventRWException.class, JacksonException.class, IllegalArgumentException.class);
        // Explicit, so a transient DB error wrapping a non-retryable cause is still retried (the outermost match wins).
        handler.addRetryableExceptions(TransientDataAccessException.class, DataAccessResourceFailureException.class);
        return handler;
    }
}
