package vn.danang.polaris.events.avro.order;

import org.apache.avro.specific.SpecificRecord;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.transport.AvroPayloadMapper;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.events.order.OrderLifecycleEvent;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/** Maps the JSON payload of an {@code OutgoingEvent} on {@code polaris.order.lifecycle} to the Avro payload. */
public final class OrderLifecyclePayloadMapper implements AvroPayloadMapper {

    private final JsonMapper json;

    public OrderLifecyclePayloadMapper(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String destination() {
        return OrderEvents.DESTINATION;
    }

    @Override
    public SpecificRecord toPayload(OutgoingEvent event) {
        return OrderLifecycleAvroMapper.toAvro(json.readValue(event.payload(), OrderLifecycleEvent.class));
    }
}
