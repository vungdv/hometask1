package vn.danang.polaris.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.kafka.KafkaMessageFactory;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.cloudevents.CloudEventPayloads;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.order.service.OrderService;

/**
 * The Polaris half of the Avro lifecycle (ADR-0021) against real PostgreSQL and Kafka and an in-memory Schema Registry:
 * a shipment report that arrives as a CloudEvent with an Avro payload moves the order, and the milestone it triggers
 * leaves Polaris through the outbox as a CloudEvent whose payload is Avro. Schemas are registered up front, as the
 * schema-init gate does, because Polaris runs with {@code auto-register=false}.
 */
@SpringBootTest(properties = {
        "polaris.events.format=avro",
        "polaris.events.avro.schema-registry-url=" + ShipmentProgressAvroKafkaIntegrationTest.REGISTRY })
@Import({ TestcontainersConfiguration.class, ShipmentProgressAvroKafkaIntegrationTest.Topics.class })
class ShipmentProgressAvroKafkaIntegrationTest {

    static final String SCOPE = "polaris-avro-lifecycle-it";
    static final String REGISTRY = "mock://" + SCOPE;
    private static final String SKU = "F3-AVRO-SKU";
    private static final String PARTNER = "partner-a";

    static {
        try {
            var registry = MockSchemaRegistry.getClientForScope(SCOPE);
            registry.register(OrderEvents.DESTINATION + "-value",
                    new AvroSchema(vn.danang.polaris.events.avro.order.OrderLifecycleEvent.getClassSchema()));
            registry.register(FulfilmentEvents.DESTINATION + "-value",
                    new AvroSchema(vn.danang.polaris.events.avro.fulfilment.ShipmentEvent.getClassSchema()));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderService orderService;
    @Autowired private KafkaContainer kafka;

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic shipmentsTopic() {
            return TopicBuilder.name(FulfilmentEvents.DESTINATION).partitions(1).replicas(1).build();
        }
    }

    @BeforeEach
    void createProduct() {
        cleanup();
        Product product = new Product();
        product.setSku(SKU);
        product.setName("Avro Shipment Test Item");
        product.setPrice(new BigDecimal("12.50"));
        product.setStockQty(1000);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(product);
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> productRepository.findBySku(SKU).ifPresent(p -> {
            orderRepository.findAll().stream()
                    .filter(o -> o.getItems().stream().anyMatch(i -> i.getProduct().getId().equals(p.getId())))
                    .forEach(o -> {
                        jdbc.sql("DELETE FROM outbox_events WHERE event_key = ?").param(o.getOrderNumber()).update();
                        orderRepository.delete(o);
                    });
            productRepository.delete(p);
        }));
    }

    @Test
    @DisplayName("an Avro PACKED report parcels the order, and the milestone is published as an Avro CloudEvent")
    void avroReportDrivesTheOrderAndTheMilestoneIsAvro() throws Exception {
        String orderNumber = orderService.place(1L, List.of(new OrderItemRequest(SKU, 1)), null).order().getOrderNumber();
        orderService.claimOrder(orderNumber, PARTNER);

        reportAsAvro(new ShipmentEvent(orderNumber, "S-" + orderNumber, PARTNER, ShipmentStep.PACKED, Instant.now()));

        await().atMost(Duration.ofSeconds(60)).until(() -> "PARCELED".equals(statusOf(orderNumber)));
        ConsumerRecord<String, byte[]> parceled = awaitRecord(orderNumber, "vn.danang.polaris.order.parceled.v1");
        CloudEvent event = KafkaMessageFactory.createReader(parceled).toEvent();
        assertThat(event.getDataContentType()).isEqualTo("application/avro");
        try (var codec = new AvroEventCodec(REGISTRY, false)) {
            vn.danang.polaris.events.avro.order.OrderLifecycleEvent payload = codec.decode(OrderEvents.DESTINATION,
                    event.getData().toBytes());
            var lifecycle = OrderLifecycleAvroMapper.fromAvro(payload);
            assertThat(lifecycle.orderNumber()).isEqualTo(orderNumber);
            assertThat(lifecycle.status().name()).isEqualTo("PARCELED");
        }
    }

    private void reportAsAvro(ShipmentEvent report) throws Exception {
        try (var codec = new AvroEventCodec(REGISTRY, false)) {
            var encoded = new CloudEventPayloads(null, codec, true).writeShipment(report);
            CloudEvent event = CloudEventBuilder.v1().withId(UUID.randomUUID().toString()).withType(report.step().type())
                    .withSource(URI.create(FulfilmentEvents.SOURCE)).withDataContentType(encoded.contentType())
                    .withData(encoded.data()).build();
            var config = Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            try (var producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer())) {
                var message = KafkaMessageFactory.createWriter(FulfilmentEvents.DESTINATION).writeBinary(event);
                producer.send(new ProducerRecord<>(FulfilmentEvents.DESTINATION, null, report.orderNumber(), message.value(),
                        message.headers())).get();
            }
        }
    }

    private ConsumerRecord<String, byte[]> awaitRecord(String key, String ceType) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "avro-it-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(OrderEvents.DESTINATION));
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key()) && ceType.equals(KafkaMessageFactory.createReader(r).toEvent().getType())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("no " + ceType + " record for " + key);
    }

    private String statusOf(String orderNumber) {
        return jdbc.sql("SELECT status FROM orders WHERE order_number = ?").param(orderNumber).query(String.class).single();
    }
}
