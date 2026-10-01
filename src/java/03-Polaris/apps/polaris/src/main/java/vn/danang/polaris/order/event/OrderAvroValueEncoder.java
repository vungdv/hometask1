package vn.danang.polaris.order.event;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.EventHeaders;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.events.order.OrderLifecycleEvent;
import vn.danang.polaris.outbox.kafka.EventValueEncoder;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Encodes Order's recorded JSON payload as registry-framed Avro at hand-off time (avro experiment). The outbox keeps
 * storing JSON, so the registry is on the relay's path: while it is unreachable the event stays {@code PENDING} and
 * the relay retries, exactly as it does for an unreachable broker.
 */
class OrderAvroValueEncoder implements EventValueEncoder {

    private final AvroEventCodec codec;
    private final JsonMapper mapper;

    OrderAvroValueEncoder(AvroEventCodec codec, JsonMapper mapper) {
        this.codec = codec;
        this.mapper = mapper;
    }

    @Override
    public byte[] encode(OutgoingEvent event) {
        if (!OrderEvents.DESTINATION.equals(event.destination())) {
            throw new IllegalArgumentException("No Avro contract for destination " + event.destination());
        }
        OrderLifecycleEvent payload = mapper.readValue(event.payload(), OrderLifecycleEvent.class);
        return codec.encode(event.destination(), OrderLifecycleAvroMapper.toAvro(payload));
    }

    @Override
    public String contentType() {
        return EventHeaders.AVRO_CONTENT_TYPE;
    }
}
