package vn.danang.polaris.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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

import io.micrometer.core.instrument.MeterRegistry;
import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.events.EventHeaders;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.fulfilment.ShipmentAvroMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.order.service.OrderService;

/**
 * F3 at the Kafka level against real PostgreSQL and Kafka: shipment reports on {@value FulfilmentEvents#DESTINATION}
 * drive the order and announce each milestone once through the outbox (TR-O4, TR-O5); anything else is a counted no-op.
 * The test topic has one partition, so a trailing "sentinel" report proves every earlier record was consumed.
 */
@SpringBootTest(properties = "polaris.avro.schema-registry-url=mock://shipment-progress-it")
@Import({ TestcontainersConfiguration.class, ShipmentProgressKafkaIntegrationTest.Topics.class })
class ShipmentProgressKafkaIntegrationTest {

    private static final String SKU = "F3-SHIP-SKU";
    private static final String PARTNER = "partner-a";

    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderService orderService;
    @Autowired private KafkaContainer kafka;
    @Autowired private MeterRegistry meters;

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        /** Fulfilment owns this topic in production; here it must exist before the listener starts. */
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
        product.setName("Shipment Test Item");
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

    private String claimedOrder(String partner) {
        String orderNumber = orderService.place(1L, List.of(new OrderItemRequest(SKU, 1)), null).order().getOrderNumber();
        orderService.claimOrder(orderNumber, partner);
        return orderNumber;
    }

    private void report(String orderNumber, String partner, ShipmentStep step) {
        var payload = new ShipmentEvent(orderNumber, "S-" + orderNumber, partner, step, Instant.now());
        var config = Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
                var codec = new AvroEventCodec("mock://shipment-progress-it", true)) {
            var record = new ProducerRecord<String, byte[]>(FulfilmentEvents.DESTINATION, null, orderNumber,
                    codec.encode(FulfilmentEvents.DESTINATION, ShipmentAvroMapper.toAvro(payload)));
            record.headers().add(EventHeaders.ID, UUID.randomUUID().toString().getBytes());
            record.headers().add(EventHeaders.TYPE, step.type().getBytes());
            record.headers().add(EventHeaders.SOURCE, FulfilmentEvents.SOURCE.getBytes());
            record.headers().add(EventHeaders.CONTENT_TYPE, EventHeaders.AVRO_CONTENT_TYPE.getBytes());
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends a valid PACKED for a fresh order and waits until it is applied: everything sent before it was consumed. */
    private void drain() {
        String sentinel = claimedOrder(PARTNER);
        report(sentinel, PARTNER, ShipmentStep.PACKED);
        await().atMost(Duration.ofSeconds(60)).until(() -> "PARCELED".equals(statusOf(sentinel)));
    }

    private String statusOf(String orderNumber) {
        return jdbc.sql("SELECT status FROM orders WHERE order_number = ?").param(orderNumber).query(String.class).single();
    }

    private List<String> milestones(String orderNumber) {
        return jdbc.sql("SELECT event_type FROM outbox_events WHERE event_key = ? ORDER BY id")
                .param(orderNumber).query(String.class).list();
    }

    private double ignored(String step, String reason) {
        var counter = meters.find("polaris.order.shipment.reports")
                .tags("step", step, "outcome", "ignored", "reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("PACKED → DISPATCHED → DELIVERED reports → order DELIVERED, three milestone events in order with the partner")
    void reports_driveOrderToDelivered() {
        String orderNumber = claimedOrder(PARTNER);

        report(orderNumber, PARTNER, ShipmentStep.PACKED);
        report(orderNumber, PARTNER, ShipmentStep.DISPATCHED);
        report(orderNumber, PARTNER, ShipmentStep.DELIVERED);

        await().atMost(Duration.ofSeconds(60)).until(() -> "DELIVERED".equals(statusOf(orderNumber)));
        assertThat(milestones(orderNumber)).containsExactly(
                "vn.danang.polaris.order.placed.v1", "vn.danang.polaris.order.confirmed.v1",
                "vn.danang.polaris.order.parceled.v1", "vn.danang.polaris.order.delivering.v1",
                "vn.danang.polaris.order.delivered.v1");
        String payload = jdbc.sql("SELECT payload FROM outbox_events WHERE event_key = ? AND event_type = ?")
                .params(orderNumber, "vn.danang.polaris.order.delivered.v1").query(String.class).single();
        assertThat(payload).contains("\"assignedPartner\":\"" + PARTNER + "\"").contains("\"status\":\"DELIVERED\"")
                .contains("\"totalAmount\":\"12.50\"");
    }

    @Test
    @DisplayName("Wrong partner, duplicate, late and cancelled-order reports → no change, no event, counted by reason")
    void unexpectedReports_areCountedNoOps() {
        String wrongPartner = claimedOrder(PARTNER);
        String duplicate = claimedOrder(PARTNER);
        String late = claimedOrder(PARTNER);
        String cancelled = claimedOrder(PARTNER);
        orderService.cancelOrder(cancelled);
        double wrong0 = ignored("packed", "wrong_partner");
        double dup0 = ignored("packed", "duplicate");
        double late0 = ignored("packed", "late");
        double cancelled0 = ignored("packed", "cancelled");
        double unknown0 = ignored("packed", "unknown_order");
        double outOfOrder0 = ignored("delivered", "out_of_order");

        report(wrongPartner, "partner-b", ShipmentStep.PACKED);
        report(duplicate, PARTNER, ShipmentStep.PACKED);
        report(duplicate, PARTNER, ShipmentStep.PACKED);
        report(late, PARTNER, ShipmentStep.PACKED);
        report(late, PARTNER, ShipmentStep.DISPATCHED);
        report(late, PARTNER, ShipmentStep.DELIVERED);
        report(late, PARTNER, ShipmentStep.PACKED);
        report(cancelled, PARTNER, ShipmentStep.PACKED);
        report("ORD-DOES-NOT-EXIST", PARTNER, ShipmentStep.PACKED);
        report(wrongPartner, PARTNER, ShipmentStep.DELIVERED);
        drain();

        assertThat(statusOf(wrongPartner)).isEqualTo("CONFIRMED");
        assertThat(milestones(wrongPartner)).hasSize(2);
        assertThat(statusOf(duplicate)).isEqualTo("PARCELED");
        assertThat(milestones(duplicate)).as("same report twice → one milestone").hasSize(3)
                .endsWith("vn.danang.polaris.order.parceled.v1");
        assertThat(statusOf(late)).isEqualTo("DELIVERED");
        assertThat(milestones(late)).hasSize(5);
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
        assertThat(milestones(cancelled)).doesNotContain("vn.danang.polaris.order.parceled.v1");

        assertThat(ignored("packed", "wrong_partner") - wrong0).isEqualTo(1);
        assertThat(ignored("packed", "duplicate") - dup0).isEqualTo(1);
        assertThat(ignored("packed", "late") - late0).isEqualTo(1);
        assertThat(ignored("packed", "cancelled") - cancelled0).isEqualTo(1);
        assertThat(ignored("packed", "unknown_order") - unknown0).isEqualTo(1);
        assertThat(ignored("delivered", "out_of_order") - outOfOrder0).isEqualTo(1);
    }
}
