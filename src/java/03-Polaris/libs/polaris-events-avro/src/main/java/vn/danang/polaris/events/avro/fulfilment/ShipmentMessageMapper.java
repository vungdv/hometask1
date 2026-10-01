package vn.danang.polaris.events.avro.fulfilment;

import java.time.temporal.ChronoUnit;

import org.apache.avro.specific.SpecificRecord;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.transport.AvroMessageMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/** Maps an {@code OutgoingEvent} on {@code polaris.fulfilment.shipments} to the {@link ShipmentMessage}. */
public final class ShipmentMessageMapper implements AvroMessageMapper {

    private final JsonMapper json;

    public ShipmentMessageMapper(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String destination() {
        return FulfilmentEvents.DESTINATION;
    }

    @Override
    public SpecificRecord toMessage(OutgoingEvent event) {
        ShipmentEvent payload = json.readValue(event.payload(), ShipmentEvent.class);
        return ShipmentMessage.newBuilder()
                .setId(event.id().toString())
                .setType(event.type())
                .setSource(event.source())
                .setTime(event.time().truncatedTo(ChronoUnit.MILLIS))
                .setData(ShipmentAvroMapper.toAvro(payload))
                .build();
    }
}
