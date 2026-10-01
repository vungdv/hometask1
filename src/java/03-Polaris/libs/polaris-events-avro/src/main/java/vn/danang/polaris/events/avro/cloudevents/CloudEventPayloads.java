package vn.danang.polaris.events.avro.cloudevents;

import java.nio.charset.StandardCharsets;

import io.cloudevents.CloudEvent;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.fulfilment.ShipmentAvroMapper;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.events.order.OrderLifecycleEvent;

/**
 * Reads and writes the {@code data} of a CloudEvent for the two Polaris contracts, in either format.
 *
 * <p><b>Reading follows the event's {@code datacontenttype}</b>, not configuration: {@code application/avro} is decoded
 * through the Schema Registry, anything else as JSON. A consumer therefore handles both formats at once, which is the
 * dual-read period of a migration. <b>Writing follows the configured format</b>. Either way the caller gets the
 * existing contract record, so the business code is the same in both formats.
 */
public final class CloudEventPayloads {

    /** {@code datacontenttype} of an Avro payload in the Confluent wire format. */
    public static final String AVRO_CONTENT_TYPE = "application/avro";
    public static final String JSON_CONTENT_TYPE = "application/json";

    /** A payload ready to be put in a CloudEvent. */
    public record Encoded(String contentType, byte[] data) {
    }

    private final JsonMapper json;
    private final AvroEventCodec codec;
    private final boolean writeAvro;

    /**
     * @param codec     the Avro codec, or {@code null} when no Schema Registry is configured (then Avro events cannot be read)
     * @param writeAvro whether {@link #writeShipment} produces Avro; requires a codec
     */
    public CloudEventPayloads(JsonMapper json, AvroEventCodec codec, boolean writeAvro) {
        if (writeAvro && codec == null) {
            throw new IllegalArgumentException("Writing Avro needs a Schema Registry (polaris.events.avro.schema-registry-url)");
        }
        this.json = json;
        this.codec = codec;
        this.writeAvro = writeAvro;
    }

    public OrderLifecycleEvent readOrderLifecycle(CloudEvent event) {
        byte[] data = event.getData().toBytes();
        if (isAvro(event)) {
            vn.danang.polaris.events.avro.order.OrderLifecycleEvent avro = requireCodec(event).decode(OrderEvents.DESTINATION, data);
            return OrderLifecycleAvroMapper.fromAvro(avro);
        }
        return json.readValue(data, OrderLifecycleEvent.class);
    }

    public ShipmentEvent readShipment(CloudEvent event) {
        byte[] data = event.getData().toBytes();
        if (isAvro(event)) {
            vn.danang.polaris.events.avro.fulfilment.ShipmentEvent avro = requireCodec(event).decode(FulfilmentEvents.DESTINATION, data);
            return ShipmentAvroMapper.fromAvro(avro);
        }
        return json.readValue(data, ShipmentEvent.class);
    }

    public Encoded writeShipment(ShipmentEvent shipment) {
        if (writeAvro) {
            return new Encoded(AVRO_CONTENT_TYPE, codec.encode(FulfilmentEvents.DESTINATION, ShipmentAvroMapper.toAvro(shipment)));
        }
        return new Encoded(JSON_CONTENT_TYPE, json.writeValueAsString(shipment).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isAvro(CloudEvent event) {
        return AVRO_CONTENT_TYPE.equals(event.getDataContentType());
    }

    private AvroEventCodec requireCodec(CloudEvent event) {
        if (codec == null) {
            throw new IllegalStateException("ce_id=" + event.getId() + " carries Avro but no Schema Registry is configured "
                    + "(polaris.events.avro.schema-registry-url)");
        }
        return codec;
    }
}
