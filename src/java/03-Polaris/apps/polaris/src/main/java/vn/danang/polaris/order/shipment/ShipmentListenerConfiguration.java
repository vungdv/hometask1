package vn.danang.polaris.order.shipment;

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
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.order.service.ShipmentProgressService;

/**
 * Wires Order's shipment-report listener: one container in consumer group {@code order.shipments}, trace context
 * continued from the record's {@code traceparent}. A failing record is retried with exponential backoff, then logged
 * at ERROR with its coordinates and skipped, so a poison record never blocks the partition (same policy as the
 * emulator's offer listeners). {@code auto.offset.reset=earliest}: a first start replays the topic, which the
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
    ConcurrentMessageListenerContainer<String, byte[]> shipmentReportListener(ConsumerFactory<?, ?> applicationConsumerFactory,
            ShipmentListenerProperties config, ShipmentReportHandler handler, ObservationRegistry observationRegistry) {
        var consumerFactory = new DefaultKafkaConsumerFactory<>(applicationConsumerFactory.getConfigurationProperties(),
                new StringDeserializer(), new ByteArrayDeserializer());
        var properties = new ContainerProperties(config.topic());
        properties.setGroupId(config.groupId());
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
        var backOff = new ExponentialBackOffWithMaxRetries(config.retries());
        backOff.setInitialInterval(config.backoffInitial().toMillis());
        backOff.setMultiplier(2.0);
        container.setCommonErrorHandler(new DefaultErrorHandler((record, e) -> log.error(
                "Shipment report skipped after retries topic={} partition={} offset={} key={} error={}",
                record.topic(), record.partition(), record.offset(), record.key(), e.toString()), backOff));
        return container;
    }
}
