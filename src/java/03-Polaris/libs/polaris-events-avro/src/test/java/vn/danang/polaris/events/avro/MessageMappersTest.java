package vn.danang.polaris.events.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.fulfilment.ShipmentAvroMapper;
import vn.danang.polaris.events.avro.fulfilment.ShipmentMessage;
import vn.danang.polaris.events.avro.fulfilment.ShipmentMessageMapper;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.avro.order.OrderLifecycleMessage;
import vn.danang.polaris.events.avro.order.OrderLifecycleMessageMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

@DisplayName("OutgoingEvent -> Avro message adapters")
class MessageMappersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ID = UUID.fromString("6f1b1c0e-52a4-4f43-9a8e-1d2b3c4d5e6f");
    private static final Instant TIME = Instant.parse("2026-09-29T10:15:30.123456Z");

    @Test
    @DisplayName("order: metadata comes from the OutgoingEvent, the payload from its JSON, time is milliseconds")
    void orderMessage() {
        var event = new OutgoingEvent(ID, OrderEvents.PLACED_V1, OrderEvents.SOURCE, TIME, OrderEvents.DESTINATION,
                "ORD-10042", JSON.writeValueAsString(AvroFixtures.sample()), null);

        OrderLifecycleMessage message = (OrderLifecycleMessage) new OrderLifecycleMessageMapper(JSON).toMessage(event);

        assertThat(new OrderLifecycleMessageMapper(JSON).destination()).isEqualTo("polaris.order.lifecycle");
        assertThat(message.getId()).isEqualTo(ID.toString());
        assertThat(message.getType()).isEqualTo(OrderEvents.PLACED_V1);
        assertThat(message.getSource()).isEqualTo(OrderEvents.SOURCE);
        assertThat(message.getTime()).isEqualTo(Instant.parse("2026-09-29T10:15:30.123Z"));
        assertThat(OrderLifecycleAvroMapper.fromAvro(message.getData())).isEqualTo(AvroFixtures.sample());
    }

    @Test
    @DisplayName("shipment: same adapter shape, different message schema")
    void shipmentMessage() {
        var shipment = new ShipmentEvent("ORD-1", "SHP-ORD-1", "partner-north", ShipmentStep.PACKED,
                Instant.parse("2026-09-29T10:15:30.123Z"));
        var event = new OutgoingEvent(ID, ShipmentStep.PACKED.type(), FulfilmentEvents.SOURCE, TIME,
                FulfilmentEvents.DESTINATION, "ORD-1", JSON.writeValueAsString(shipment), null);

        ShipmentMessage message = (ShipmentMessage) new ShipmentMessageMapper(JSON).toMessage(event);

        assertThat(new ShipmentMessageMapper(JSON).destination()).isEqualTo("polaris.fulfilment.shipments");
        assertThat(message.getId()).isEqualTo(ID.toString());
        assertThat(message.getType()).isEqualTo(ShipmentStep.PACKED.type());
        assertThat(message.getSource()).isEqualTo(FulfilmentEvents.SOURCE);
        assertThat(ShipmentAvroMapper.fromAvro(message.getData())).isEqualTo(shipment);
    }

    @Test
    @DisplayName("a payload that does not match the contract fails the mapping (the relay would retry)")
    void unmappablePayload() {
        var event = new OutgoingEvent(ID, OrderEvents.PLACED_V1, OrderEvents.SOURCE, TIME, OrderEvents.DESTINATION,
                "ORD-1", "{\"status\":\"PLACED\"}", null);

        assertThatThrownBy(() -> new OrderLifecycleMessageMapper(JSON).toMessage(event)).isInstanceOf(RuntimeException.class);
    }
}
