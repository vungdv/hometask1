package vn.danang.polaris.events.avro.fulfilment;

import org.apache.avro.specific.SpecificRecord;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.transport.AvroPayloadMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/** Maps the JSON payload of an {@code OutgoingEvent} on {@code polaris.fulfilment.shipments} to the Avro payload. */
public final class ShipmentPayloadMapper implements AvroPayloadMapper {

    private final JsonMapper json;

    public ShipmentPayloadMapper(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String destination() {
        return FulfilmentEvents.DESTINATION;
    }

    @Override
    public SpecificRecord toPayload(OutgoingEvent event) {
        return ShipmentAvroMapper.toAvro(json.readValue(event.payload(), ShipmentEvent.class));
    }
}
