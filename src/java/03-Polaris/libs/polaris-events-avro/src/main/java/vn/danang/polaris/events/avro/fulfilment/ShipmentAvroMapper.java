package vn.danang.polaris.events.avro.fulfilment;

import java.time.temporal.ChronoUnit;

import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;

/** Maps the shipment contract record to its generated Avro twin and back. */
public final class ShipmentAvroMapper {

    private ShipmentAvroMapper() {
    }

    public static vn.danang.polaris.events.avro.fulfilment.ShipmentEvent toAvro(ShipmentEvent e) {
        return vn.danang.polaris.events.avro.fulfilment.ShipmentEvent.newBuilder()
                .setOrderNumber(e.orderNumber())
                .setShipmentId(e.shipmentId())
                .setPartnerId(e.partnerId())
                .setStep(vn.danang.polaris.events.avro.fulfilment.ShipmentStep.valueOf(e.step().name()))
                .setOccurredAt(e.occurredAt().truncatedTo(ChronoUnit.MILLIS))
                .build();
    }

    public static ShipmentEvent fromAvro(vn.danang.polaris.events.avro.fulfilment.ShipmentEvent a) {
        return new ShipmentEvent(a.getOrderNumber(), a.getShipmentId(), a.getPartnerId(),
                ShipmentStep.valueOf(a.getStep().name()), a.getOccurredAt());
    }
}
