package vn.danang.polaris.events.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.fulfilment.ShipmentAvroMapper;
import vn.danang.polaris.events.avro.fulfilment.ShipmentPayloadMapper;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.avro.order.OrderLifecyclePayloadMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

@DisplayName("OutgoingEvent -> Avro payload adapters")
class PayloadMappersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ID = UUID.fromString("6f1b1c0e-52a4-4f43-9a8e-1d2b3c4d5e6f");
    
    private static final Instant TIME = Instant.parse("2026-09-29T10:15:30.123Z");

    @Test
    @DisplayName("order: the payload is built from the OutgoingEvent JSON; metadata is not part of it")
    void orderPayload() {
        var event = new OutgoingEvent(ID, OrderEvents.PLACED_V1, OrderEvents.SOURCE, TIME, OrderEvents.DESTINATION,
                "ORD-10042", JSON.writeValueAsString(AvroFixtures.sample()), null);
        var mapper = new OrderLifecyclePayloadMapper(JSON);

        var payload = (vn.danang.polaris.events.avro.order.OrderLifecycleEvent) mapper.toPayload(event);

        assertThat(mapper.destination()).isEqualTo("polaris.order.lifecycle");
        assertThat(OrderLifecycleAvroMapper.fromAvro(payload)).isEqualTo(AvroFixtures.sample());
    }

    @Test
    @DisplayName("shipment: same adapter shape, different payload schema")
    void shipmentPayload() {
        var shipment = new ShipmentEvent("ORD-1", "SHP-ORD-1", "partner-north", ShipmentStep.PACKED, TIME);
        var event = new OutgoingEvent(ID, ShipmentStep.PACKED.type(), FulfilmentEvents.SOURCE, TIME,
                FulfilmentEvents.DESTINATION, "ORD-1", JSON.writeValueAsString(shipment), null);
        var mapper = new ShipmentPayloadMapper(JSON);

        var payload = (vn.danang.polaris.events.avro.fulfilment.ShipmentEvent) mapper.toPayload(event);

        assertThat(mapper.destination()).isEqualTo("polaris.fulfilment.shipments");
        assertThat(ShipmentAvroMapper.fromAvro(payload)).isEqualTo(shipment);
    }

    @Test
    @DisplayName("a payload that does not match the contract fails the mapping (the relay would retry)")
    void unmappablePayload() {
        var event = new OutgoingEvent(ID, OrderEvents.PLACED_V1, OrderEvents.SOURCE, TIME, OrderEvents.DESTINATION,
                "ORD-1", "{\"status\":\"PLACED\"}", null);

        assertThatThrownBy(() -> new OrderLifecyclePayloadMapper(JSON).toPayload(event)).isInstanceOf(RuntimeException.class);
    }
}
