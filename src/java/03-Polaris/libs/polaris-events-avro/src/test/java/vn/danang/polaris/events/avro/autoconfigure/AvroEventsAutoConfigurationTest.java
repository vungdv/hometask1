package vn.danang.polaris.events.avro.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.cloudevents.CloudEventPayloads;
import vn.danang.polaris.events.avro.transport.AvroKafkaEventTransport;
import vn.danang.polaris.outbox.autoconfigure.OutboxAutoConfiguration;
import vn.danang.polaris.outbox.autoconfigure.OutboxKafkaAutoConfiguration;
import vn.danang.polaris.outbox.kafka.KafkaEventTransport;
import vn.danang.polaris.outbox.transport.EventTransport;

@DisplayName("Avro opt-in auto-configuration")
class AvroEventsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class,
                    OutboxAutoConfiguration.class, AvroEventsAutoConfiguration.class, OutboxKafkaAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withPropertyValues("polaris.outbox.relay.enabled=false");

    @Test
    @DisplayName("default: the Avro transport is the primary one")
    void defaultIsAvro() {
        runner.withPropertyValues("polaris.events.avro.schema-registry-url=mock://auto").run(context -> {
            assertThat(context).hasSingleBean(EventTransport.class);
            assertThat(context.getBean(EventTransport.class)).isInstanceOf(AvroKafkaEventTransport.class);
            assertThat(context).hasSingleBean(CloudEventPayloads.class);
        });
    }

    @Test
    @DisplayName("format=json restores the ADR-0019 transport")
    void jsonFormatKeepsTheJsonTransport() {
        runner.withPropertyValues("polaris.events.format=json").run(context -> {
            assertThat(context.getBean(EventTransport.class)).isInstanceOf(KafkaEventTransport.class);
            assertThat(context).doesNotHaveBean(AvroEventCodec.class);
        });
    }

    @Test
    @DisplayName("the Avro transport bean is registered as primary")
    void avroTransportIsPrimary() {
        runner.withPropertyValues("polaris.events.avro.schema-registry-url=mock://auto-primary").run(context ->
                assertThat(context.getBeanFactory().getBeanDefinition("avroKafkaEventTransport").isPrimary()).isTrue());
    }

    @Test
    @DisplayName("format=json with a registry: still the JSON transport, but Avro events can be read")
    void registryOnlyEnablesReading() {
        runner.withPropertyValues("polaris.events.format=json", "polaris.events.avro.schema-registry-url=mock://auto-read").run(context -> {
            assertThat(context.getBean(EventTransport.class)).isInstanceOf(KafkaEventTransport.class);
            assertThat(context).hasSingleBean(AvroEventCodec.class);
        });
    }

    @Test
    @DisplayName("Avro (the default) without a registry fails at startup and names the missing setting")
    void avroWithoutRegistryFails() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("polaris.events.avro.schema-registry-url");
        });
    }
}
