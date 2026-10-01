package vn.danang.polaris.order.shipment;

import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.EventMeta;
import vn.danang.polaris.events.avro.fulfilment.ShipmentAvroMapper;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.order.service.ShipmentProgressService;
import vn.danang.polaris.order.service.ShipmentProgressService.Outcome;

/**
 * Reads one record of the shipments topic as Avro with its event headers (avro experiment, replaces ADR-0019 §4.1 CloudEvents) and applies it to the order.
 * Types that are not shipment steps are ignored so new event types never break it. Every no-op is logged with its
 * reason and counted in {@code polaris.order.shipment.reports} (TR-X2); none of them throws, so nothing is retried
 * forever. Only a malformed record or an infrastructure failure throws, and the container's error handler bounds that.
 */
public class ShipmentReportHandler {

    private static final Logger log = LoggerFactory.getLogger(ShipmentReportHandler.class);

    private final ShipmentProgressService service;
    private final AvroEventCodec codec;
    private final MeterRegistry meters;

    public ShipmentReportHandler(ShipmentProgressService service, AvroEventCodec codec, MeterRegistry meters) {
        this.service = service;
        this.codec = codec;
        this.meters = meters;
    }

    public void handle(ConsumerRecord<String, byte[]> record) {
        EventMeta event = EventMeta.of(record.headers());
        Optional<ShipmentStep> step = ShipmentStep.fromType(event.type());
        if (step.isEmpty()) {
            log.debug("Ignoring event event_id={} event_type={}", event.id(), event.type());
            return;
        }
        vn.danang.polaris.events.avro.fulfilment.ShipmentEvent avro = codec.decode(record.topic(), record.value());
        ShipmentEvent report = ShipmentAvroMapper.fromAvro(avro);
        if (report.step() != step.get()) {
            count(step.get(), "ignored", "type_mismatch");
            log.warn("Shipment report ignored orderNumber={} event_id={} event_type={} reason=type_mismatch step={}",
                    report.orderNumber(), event.id(), event.type(), report.step());
            return;
        }
        Outcome outcome = service.apply(report);
        if (outcome.applied()) {
            count(report.step(), "applied", outcome.decision().next().name().toLowerCase());
            log.info("Shipment report applied orderNumber={} event_id={} step={} partnerId={} status={}",
                    report.orderNumber(), event.id(), report.step(), report.partnerId(), outcome.decision().next());
        } else {
            String reason = outcome.decision().reason().tag();
            count(report.step(), "ignored", reason);
            log.warn("Shipment report ignored orderNumber={} event_id={} step={} partnerId={} reason={}",
                    report.orderNumber(), event.id(), report.step(), report.partnerId(), reason);
        }
    }

    private void count(ShipmentStep step, String outcome, String reason) {
        meters.counter("polaris.order.shipment.reports",
                "step", step.name().toLowerCase(), "outcome", outcome, "reason", reason).increment();
    }
}
