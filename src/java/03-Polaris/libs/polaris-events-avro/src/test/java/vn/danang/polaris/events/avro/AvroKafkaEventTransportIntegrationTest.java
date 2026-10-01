package vn.danang.polaris.events.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.KafkaMessageFactory;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.fulfilment.ShipmentAvroMapper;
import vn.danang.polaris.events.avro.fulfilment.ShipmentPayloadMapper;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.avro.order.OrderLifecyclePayloadMapper;
import vn.danang.polaris.events.avro.transport.AvroKafkaEventTransport;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.outbox.kafka.KafkaEventTransport;
import vn.danang.polaris.outbox.trace.W3cTraceContext;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * {@link AvroKafkaEventTransport} against a real Kafka broker and a real Confluent Schema Registry: an
 * {@code OutgoingEvent} for each destination arrives as one CloudEvents binary-mode record keyed by the
 * aggregate, with the {@code ce_*} attributes in headers and an Avro payload as data; the registry holds the payload
 * schema.
 */
@Testcontainers
class AvroKafkaEventTransportIntegrationTest {

    static final Network NETWORK = Network.newNetwork();
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withNetwork(NETWORK).withNetworkAliases("kafka");
    static final GenericContainer<?> REGISTRY = new GenericContainer<>("confluentinc/cp-schema-registry:8.0.0")
            .withNetwork(NETWORK).withExposedPorts(8081)
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9093")
            .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
            .waitingFor(Wait.forHttp("/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    static String registryUrl;
    static AvroEventCodec codec;
    static AvroKafkaEventTransport transport;

    @BeforeAll
    static void start() throws Exception {
        KAFKA.start();
        REGISTRY.start();
        registryUrl = "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(OrderEvents.DESTINATION, 1, (short) 1),
                    new NewTopic(FulfilmentEvents.DESTINATION, 1, (short) 1))).all().get();
        }
        codec = new AvroEventCodec(registryUrl, true);
        transport = transport(codec);
    }

    @AfterAll
    static void stop() {
        transport.close();
        codec.close();
    }

    private static AvroKafkaEventTransport transport(AvroEventCodec withCodec) {
        Map<String, Object> config = new HashMap<>(KafkaEventTransport.producerOverrides(TIMEOUT));
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        var template = new KafkaTemplate<String, byte[]>(new DefaultKafkaProducerFactory<>(config));
        return new AvroKafkaEventTransport(template, null, TIMEOUT, withCodec,
                List.of(new OrderLifecyclePayloadMapper(JSON), new ShipmentPayloadMapper(JSON)));
    }

    private static OutgoingEvent order(UUID id, String key) {
        return new OutgoingEvent(id, OrderEvents.CONFIRMED_V1, OrderEvents.SOURCE, Instant.parse("2026-09-29T10:15:30.123Z"),
                OrderEvents.DESTINATION, key, JSON.writeValueAsString(AvroFixtures.sample()),
                new W3cTraceContext("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "polaris=t1"));
    }

    @Test
    @DisplayName("an order OutgoingEvent arrives as a CloudEvent whose data is the Avro payload")
    void orderEventArrivesAsCloudEventWithAvroData() throws Exception {
        UUID id = UUID.randomUUID();
        String key = "ORD-" + id;

        transport.send(order(id, key));

        ConsumerRecord<String, byte[]> record = poll(OrderEvents.DESTINATION, key);
        assertThat(record.key()).isEqualTo(key);
        CloudEvent event = KafkaMessageFactory.createReader(record).toEvent();
        assertThat(event.getId()).isEqualTo(id.toString());
        assertThat(event.getType()).isEqualTo(OrderEvents.CONFIRMED_V1);
        assertThat(event.getSource().toString()).isEqualTo(OrderEvents.SOURCE);
        assertThat(event.getTime().toInstant()).isEqualTo(Instant.parse("2026-09-29T10:15:30.123Z"));
        assertThat(event.getDataContentType()).isEqualTo("application/avro");
        assertThat(headerNames(record)).contains("ce_specversion", "traceparent", "tracestate");
        byte[] data = event.getData().toBytes();
        assertThat(data[0]).as("Confluent wire format magic byte").isZero();
        vn.danang.polaris.events.avro.order.OrderLifecycleEvent payload = codec.decode(OrderEvents.DESTINATION, data);
        assertThat(OrderLifecycleAvroMapper.fromAvro(payload)).isEqualTo(AvroFixtures.sample());

        var registry = new CachedSchemaRegistryClient(registryUrl, 10);
        assertThat(registry.getAllSubjects()).contains(OrderEvents.DESTINATION + "-value");
        assertThat(registry.getLatestSchemaMetadata(OrderEvents.DESTINATION + "-value").getSchema())
                .contains("\"name\":\"OrderLifecycleEvent\"").doesNotContain("\"name\":\"source\"");
    }

    @Test
    @DisplayName("shipment: the same transport and the same adapter pattern deliver the shipments topic")
    void shipmentEventArrivesAsCloudEventWithAvroData() throws Exception {
        UUID id = UUID.randomUUID();
        String key = "ORD-SHP-" + id;
        var shipment = new ShipmentEvent(key, "SHP-" + key, "partner-south", ShipmentStep.DISPATCHED,
                Instant.parse("2026-09-29T10:20:00.000Z"));

        transport.send(new OutgoingEvent(id, ShipmentStep.DISPATCHED.type(), FulfilmentEvents.SOURCE,
                Instant.parse("2026-09-29T10:20:00.000Z"), FulfilmentEvents.DESTINATION, key,
                JSON.writeValueAsString(shipment), null));

        ConsumerRecord<String, byte[]> record = poll(FulfilmentEvents.DESTINATION, key);
        CloudEvent event = KafkaMessageFactory.createReader(record).toEvent();
        assertThat(event.getId()).isEqualTo(id.toString());
        assertThat(event.getType()).isEqualTo(ShipmentStep.DISPATCHED.type());
        vn.danang.polaris.events.avro.fulfilment.ShipmentEvent payload =
                codec.decode(FulfilmentEvents.DESTINATION, event.getData().toBytes());
        assertThat(ShipmentAvroMapper.fromAvro(payload)).isEqualTo(shipment);
    }

    @Test
    @DisplayName("an unknown destination or an unmappable payload fails the send, so the relay retries")
    void failuresAreFailedAttempts() {
        var unknown = new OutgoingEvent(UUID.randomUUID(), "x.v1", "/x", Instant.now(), "polaris.unknown", "K", "{}", null);
        var unmappable = new OutgoingEvent(UUID.randomUUID(), OrderEvents.PLACED_V1, OrderEvents.SOURCE, Instant.now(),
                OrderEvents.DESTINATION, "K", "{\"status\":\"PLACED\"}", null);

        assertThatThrownBy(() -> transport.send(unknown)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("polaris.unknown");
        assertThatThrownBy(() -> transport.send(unmappable)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an unreachable registry fails the send: the registry is on the relay's path")
    void unreachableRegistryFailsTheSend() {
        try (var brokenCodec = new AvroEventCodec("http://localhost:1", true);
                var broken = transport(brokenCodec)) {
            assertThatThrownBy(() -> broken.send(order(UUID.randomUUID(), "K-" + UUID.randomUUID())))
                    .isInstanceOf(Exception.class);
        }
    }

    private static List<String> headerNames(ConsumerRecord<?, ?> record) {
        List<String> names = new java.util.ArrayList<>();
        record.headers().forEach(h -> names.add(h.key()));
        return names;
    }

    private static ConsumerRecord<String, byte[]> poll(String topic, String key) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "g-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("no record for key " + key + " on " + topic);
    }
}
