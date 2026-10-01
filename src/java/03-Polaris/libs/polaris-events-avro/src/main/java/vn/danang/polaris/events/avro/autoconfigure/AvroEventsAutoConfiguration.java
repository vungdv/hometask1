package vn.danang.polaris.events.avro.autoconfigure;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.cloudevents.CloudEventPayloads;
import vn.danang.polaris.events.avro.fulfilment.ShipmentPayloadMapper;
import vn.danang.polaris.events.avro.order.OrderLifecyclePayloadMapper;
import vn.danang.polaris.events.avro.transport.AvroKafkaEventTransport;
import vn.danang.polaris.outbox.autoconfigure.OutboxAutoConfiguration;
import vn.danang.polaris.outbox.autoconfigure.OutboxKafkaAutoConfiguration;
import vn.danang.polaris.outbox.autoconfigure.OutboxProperties;
import vn.danang.polaris.outbox.kafka.KafkaEventTransport;
import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.transport.EventTransport;

/**
 * Avro layer (ADR-0021), the default on this branch. Always contributes {@link CloudEventPayloads}, so a service reads Avro and JSON events
 * alike once a registry URL is configured. Unless {@code polaris.events.format=json}, an app that has the outbox also gets
 * {@link AvroKafkaEventTransport}, the primary transport: it is ordered before the JSON {@code KafkaEventTransport}, and
 * that one yields to any {@link EventTransport}.
 */
@AutoConfiguration(after = OutboxAutoConfiguration.class, before = OutboxKafkaAutoConfiguration.class,
        afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@EnableConfigurationProperties(AvroEventsProperties.class)
public class AvroEventsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "polaris.events.avro", name = "schema-registry-url")
    @ConditionalOnMissingBean
    AvroEventCodec avroEventCodec(AvroEventsProperties properties) {
        return new AvroEventCodec(properties.avro().schemaRegistryUrl(), properties.avro().autoRegister());
    }

    @Bean
    @ConditionalOnMissingBean
    CloudEventPayloads cloudEventPayloads(JsonMapper json, ObjectProvider<AvroEventCodec> codec, AvroEventsProperties properties) {
        return new CloudEventPayloads(json, codec.getIfAvailable(), properties.format() == AvroEventsProperties.Format.AVRO);
    }

    @Bean(destroyMethod = "close")
    @Primary
    @ConditionalOnProperty(prefix = "polaris.events", name = "format", havingValue = "avro", matchIfMissing = true)
    @ConditionalOnBean({ OutboxRelayStore.class, ProducerFactory.class })
    @ConditionalOnMissingBean(EventTransport.class)
    AvroKafkaEventTransport avroKafkaEventTransport(OutboxProperties outbox, ProducerFactory<?, ?> applicationProducerFactory,
            ObjectProvider<KafkaAdmin> admin, ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<AvroEventCodec> codec, JsonMapper json) {
        AvroEventCodec avroCodec = codec.getIfAvailable(() -> {
            throw new IllegalStateException("polaris.events.format=avro needs polaris.events.avro.schema-registry-url");
        });
        var sendTimeout = outbox.kafka().sendTimeout();
        @SuppressWarnings("unchecked")
        ProducerFactory<String, byte[]> producerFactory = (ProducerFactory<String, byte[]>) applicationProducerFactory
                .copyWithConfigurationOverride(KafkaEventTransport.producerOverrides(sendTimeout));
        var template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
        return new AvroKafkaEventTransport(template, admin.getIfUnique(), sendTimeout, avroCodec,
                List.of(new OrderLifecyclePayloadMapper(json), new ShipmentPayloadMapper(json)));
    }
}
