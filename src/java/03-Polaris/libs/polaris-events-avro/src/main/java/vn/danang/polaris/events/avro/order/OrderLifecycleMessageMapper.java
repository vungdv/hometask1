package vn.danang.polaris.events.avro.order;

import java.time.temporal.ChronoUnit;

import org.apache.avro.specific.SpecificRecord;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.transport.AvroMessageMapper;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.events.order.OrderLifecycleEvent;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/** Maps an {@code OutgoingEvent} on {@code polaris.order.lifecycle} to the {@link OrderLifecycleMessage}. */
public final class OrderLifecycleMessageMapper implements AvroMessageMapper {

    private final JsonMapper json;

    public OrderLifecycleMessageMapper(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String destination() {
        return OrderEvents.DESTINATION;
    }

    @Override
    public SpecificRecord toMessage(OutgoingEvent event) {
        OrderLifecycleEvent payload = json.readValue(event.payload(), OrderLifecycleEvent.class);
        return OrderLifecycleMessage.newBuilder()
                .setId(event.id().toString())
                .setType(event.type())
                .setSource(event.source())
                .setTime(event.time().truncatedTo(ChronoUnit.MILLIS))
                .setData(OrderLifecycleAvroMapper.toAvro(payload))
                .build();
    }
}
