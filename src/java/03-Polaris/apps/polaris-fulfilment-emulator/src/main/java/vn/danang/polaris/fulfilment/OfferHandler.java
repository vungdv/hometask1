package vn.danang.polaris.fulfilment;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.EventMeta;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.events.order.OrderLifecycleEvent;

/**
 * Reads one record of the order lifecycle topic as Avro with its event headers (avro experiment, replaces ADR-0019 §4.1 CloudEvents) and offers
 * {@code order.placed.v1} orders to a partner. Every other type is ignored (DEBUG) so new event types never break it.
 */
class OfferHandler {

    private static final Logger log = LoggerFactory.getLogger(OfferHandler.class);

    private final AvroEventCodec codec;
    private final FulfilmentMetrics metrics;

    OfferHandler(AvroEventCodec codec, FulfilmentMetrics metrics) {
        this.codec = codec;
        this.metrics = metrics;
    }

    void handle(PartnerAgent partner, ConsumerRecord<String, byte[]> record) {
        EventMeta event = EventMeta.of(record.headers());
        if (!OrderEvents.PLACED_V1.equals(event.type())) {
            metrics.offer(partner.partnerId(), "ignored");
            log.debug("Ignoring event partnerId={} event_id={} event_type={}", partner.partnerId(), event.id(), event.type());
            return;
        }
        vn.danang.polaris.events.avro.order.OrderLifecycleEvent avro = codec.decode(record.topic(), record.value());
        OrderLifecycleEvent placed = OrderLifecycleAvroMapper.fromAvro(avro);
        metrics.offer(partner.partnerId(), "received");
        log.info("Offer event_id={} event_type={} orderNumber={} partnerId={}", event.id(), event.type(),
                placed.orderNumber(), partner.partnerId());
        partner.onOffer(placed.orderNumber());
    }
}
