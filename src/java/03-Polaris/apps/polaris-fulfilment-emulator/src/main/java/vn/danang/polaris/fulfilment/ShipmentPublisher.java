package vn.danang.polaris.fulfilment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import vn.danang.polaris.events.EventHeaders;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.fulfilment.ShipmentAvroMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;

/**
 * Sends one shipment event as registry-framed Avro (avro experiment, replaces the CloudEvent of ADR-0019 §4): topic
 * {@value FulfilmentEvents#DESTINATION}, key = order number, metadata in {@link EventHeaders} headers. Sent directly, not through an outbox, and never retried: a lost
 * report only stalls a demo order (TR-F4). The template has observation on, so the send is a producer span in the
 * current trace and the record carries its {@code traceparent}.
 */
class ShipmentPublisher {

    private static final Logger log = LoggerFactory.getLogger(ShipmentPublisher.class);

    private final KafkaTemplate<String, byte[]> template;
    private final AvroEventCodec codec;
    private final String topic;
    private final FulfilmentMetrics metrics;

    ShipmentPublisher(KafkaTemplate<String, byte[]> template, AvroEventCodec codec, String topic, FulfilmentMetrics metrics) {
        this.template = template;
        this.codec = codec;
        this.topic = topic;
        this.metrics = metrics;
    }

    void publish(String orderNumber, String partnerId, ShipmentStep step, Instant now) {
        var payload = new ShipmentEvent(orderNumber, "SHP-" + orderNumber, partnerId, step, now);
        String eventId = UUID.randomUUID().toString();
        byte[] value = codec.encode(topic, ShipmentAvroMapper.toAvro(payload));
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, null, orderNumber, value);
        header(record, EventHeaders.ID, eventId);
        header(record, EventHeaders.TYPE, step.type());
        header(record, EventHeaders.SOURCE, FulfilmentEvents.SOURCE);
        header(record, EventHeaders.TIME, now.toString());
        header(record, EventHeaders.CONTENT_TYPE, EventHeaders.AVRO_CONTENT_TYPE);
        template.send(record)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        metrics.shipment(partnerId, step, false);
                        log.error("Shipment event not sent orderNumber={} partnerId={} event_id={} event_type={}",
                                orderNumber, partnerId, eventId, step.type(), error);
                    }
                    else {
                        metrics.shipment(partnerId, step, true);
                        log.info("Shipment event sent orderNumber={} partnerId={} event_id={} event_type={}",
                                orderNumber, partnerId, eventId, step.type());
                    }
                });
    }

    private static void header(ProducerRecord<String, byte[]> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
