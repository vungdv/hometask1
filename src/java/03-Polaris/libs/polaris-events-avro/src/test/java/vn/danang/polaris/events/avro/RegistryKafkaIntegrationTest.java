package vn.danang.polaris.events.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import vn.danang.polaris.events.EventHeaders;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;

/**
 * The experiment end to end against a real Kafka broker and a real Confluent Schema Registry: the record carries
 * its metadata in plain {@code event-*} headers (no CloudEvents) and a registry-framed Avro value, the registry
 * enforces the compatibility mode at registration, and a v1 consumer keeps reading after the producer moves to v2.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistryKafkaIntegrationTest {

    static final String TOPIC = "polaris.order.lifecycle";
    static final String SUBJECT = TOPIC + "-value";
    static final Network NETWORK = Network.newNetwork();

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withNetwork(NETWORK).withNetworkAliases("kafka");
    static final GenericContainer<?> REGISTRY = new GenericContainer<>("confluentinc/cp-schema-registry:8.0.0")
            .withNetwork(NETWORK)
            .withExposedPorts(8081)
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9093")
            .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
            .waitingFor(Wait.forHttp("/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static String registryUrl;
    static CachedSchemaRegistryClient registry;

    @BeforeAll
    static void start() throws Exception {
        KAFKA.start();
        REGISTRY.start();
        registryUrl = "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081);
        registry = new CachedSchemaRegistryClient(registryUrl, 100);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
        put("/config/" + SUBJECT, "{\"compatibility\":\"BACKWARD_TRANSITIVE\"}");
    }

    static void put(String path, String body) throws Exception {
        var res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(registryUrl + path))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(1)
    @DisplayName("v1 is registered; a record carries event-* headers and a registry-framed Avro value")
    void v1RecordCarriesEventHeaders() throws Exception {
        registry.register(SUBJECT, new AvroSchema(AvroFixtures.V1));

        ConsumerRecord<String, byte[]> record = produceAndPoll(AvroFixtures.V1, AvroFixtures.sampleAsGeneric());

        assertThat(header(record, EventHeaders.TYPE)).isEqualTo("vn.danang.polaris.order.confirmed.v1");
        assertThat(header(record, EventHeaders.CONTENT_TYPE)).isEqualTo("application/avro");
        assertThat(record.key()).isEqualTo("ORD-10042");
        assertThat(record.value()[0]).as("Confluent wire format magic byte").isZero();
        int schemaId = java.nio.ByteBuffer.wrap(record.value(), 1, 4).getInt();
        assertThat(registry.getSchemaById(schemaId).canonicalString()).contains("OrderLifecycleEvent");
        System.out.printf("RECORD value=%d bytes (5 framing + payload), schemaId=%d%n", record.value().length, schemaId);
    }

    @Test
    @Order(2)
    @DisplayName("additive v2 registers under BACKWARD_TRANSITIVE; a v1 consumer still decodes a v2 record")
    void additiveV2IsAcceptedAndOldConsumerReads() throws Exception {
        Schema v2 = AvroFixtures.evolution("order-lifecycle-v2-additive");
        registry.register(SUBJECT, new AvroSchema(v2));

        GenericRecord rec = AvroFixtures.decode(AvroFixtures.encode(AvroFixtures.sampleAsGeneric()), AvroFixtures.V1, v2);
        rec.put("shipmentId", "SHP-7f3c");
        ConsumerRecord<String, byte[]> record = produceAndPoll(v2, rec);

        // Consumer pinned to the v1 reader schema: the writer schema comes from the registry via the schema id.
        Properties props = consumerProps();
        props.put("specific.avro.reader", "false");
        try (var deser = new KafkaAvroDeserializer(registry, Map.of("schema.registry.url", registryUrl))) {
            GenericRecord written = (GenericRecord) deser.deserialize(TOPIC, record.value());
            assertThat(written.get("shipmentId").toString()).isEqualTo("SHP-7f3c");
            GenericRecord asV1 = AvroFixtures.decode(AvroFixtures.encode(written), written.getSchema(), AvroFixtures.V1);
            assertThat(asV1.get("orderNumber").toString()).isEqualTo("ORD-10042");
        }
        assertThat(registry.getAllVersions(SUBJECT)).containsExactly(1, 2);
    }

    @Test
    @Order(3)
    @DisplayName("a breaking schema is rejected by the registry with HTTP 409 before any record is produced")
    void breakingSchemaIsRejected() throws Exception {
        Schema bad = AvroFixtures.evolution("order-lifecycle-bad-required-field");

        assertThatThrownBy(() -> registry.register(SUBJECT, new AvroSchema(bad)))
                .isInstanceOfSatisfying(RestClientException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(registry.testCompatibility(SUBJECT, new AvroSchema(bad)) ).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("compatibility is checked per subject, so the same change passes under NONE: the guard is configuration")
    void compatibilityModeIsConfiguration() throws Exception {
        String other = "polaris.scratch-value";
        registry.register(other, new AvroSchema(AvroFixtures.V1));
        put("/config/" + other, "{\"compatibility\":\"NONE\"}");

        Schema bad = AvroFixtures.evolution("order-lifecycle-bad-type-change");

        assertThat(registry.register(other, new AvroSchema(bad))).isPositive();
    }

    @Test
    @Order(5)
    @DisplayName("the production codec round-trips the generated classes through the real registry")
    void codecRoundTrip() {
        var sent = vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper.toAvro(AvroFixtures.sample());
        try (var codec = new AvroEventCodec(registryUrl, true)) {
            byte[] bytes = codec.encode("polaris.codec-check", sent);
            vn.danang.polaris.events.avro.order.OrderLifecycleEvent read = codec.decode("polaris.codec-check", bytes);
            assertThat(vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper.fromAvro(read))
                    .isEqualTo(AvroFixtures.sample());
        }
    }

    private ConsumerRecord<String, byte[]> produceAndPoll(Schema schema, GenericRecord value) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put("schema.registry.url", registryUrl);
        p.put("auto.register.schemas", "false");
        p.put("use.latest.version", "false");
        String id = UUID.randomUUID().toString();
        try (KafkaAvroSerializer ser = new KafkaAvroSerializer(registry, Map.of("schema.registry.url", registryUrl,
                "auto.register.schemas", "false"));
                KafkaProducer<String, byte[]> producer = new KafkaProducer<>(p, new StringSerializer(),
                        new org.apache.kafka.common.serialization.ByteArraySerializer())) {
            byte[] avro = ser.serialize(TOPIC, value);
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(TOPIC, null, "ORD-10042", avro);
            record.headers().add(EventHeaders.ID, id.getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.TYPE,
                    "vn.danang.polaris.order.confirmed.v1".getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.CONTENT_TYPE,
                    EventHeaders.AVRO_CONTENT_TYPE.getBytes(StandardCharsets.UTF_8));
            producer.send(record);
            producer.flush();
        }
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps(), new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    if (id.equals(header(r, EventHeaders.ID))) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("record " + id + " not consumed");
    }

    private static Properties consumerProps() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "g-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return p;
    }

    private static String header(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
