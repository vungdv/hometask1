package vn.danang.polaris.events.avro.cloudevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.AvroFixtures;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.events.order.OrderEvents;

@DisplayName("CloudEvent payloads: write by configuration, read by content type")
class CloudEventPayloadsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ShipmentEvent SHIPMENT = new ShipmentEvent("ORD-1", "SHP-ORD-1", "partner-north",
            ShipmentStep.PACKED, Instant.parse("2026-09-29T10:15:30.123Z"));

    private final AvroEventCodec codec = new AvroEventCodec("mock://payloads-" + UUID.randomUUID(), true);

    private static CloudEvent event(CloudEventPayloads.Encoded encoded) {
        return CloudEventBuilder.v1().withId("1").withType("t").withSource(URI.create("/x"))
                .withDataContentType(encoded.contentType()).withData(encoded.data()).build();
    }

    @Test
    @DisplayName("an Avro writer produces Avro that any reader with a registry decodes to the contract record")
    void avroRoundTrip() {
        var payloads = new CloudEventPayloads(JSON, codec, true);

        var encoded = payloads.writeShipment(SHIPMENT);

        assertThat(encoded.contentType()).isEqualTo("application/avro");
        assertThat(encoded.data()[0]).as("Confluent magic byte").isZero();
        assertThat(payloads.readShipment(event(encoded))).isEqualTo(SHIPMENT);
    }

    @Test
    @DisplayName("a JSON writer produces JSON, and a reader that can also read Avro still reads it (dual read)")
    void jsonWriterAndDualReader() {
        var jsonWriter = new CloudEventPayloads(JSON, null, false);
        var dualReader = new CloudEventPayloads(JSON, codec, false);

        var encoded = jsonWriter.writeShipment(SHIPMENT);

        assertThat(encoded.contentType()).isEqualTo("application/json");
        assertThat(dualReader.readShipment(event(encoded))).isEqualTo(SHIPMENT);
        var avro = new CloudEventPayloads(JSON, codec, true).writeShipment(SHIPMENT);
        assertThat(dualReader.readShipment(event(avro))).isEqualTo(SHIPMENT);
    }

    @Test
    @DisplayName("the order payload is read from Avro as the same record the JSON path produces")
    void orderLifecycleFromAvro() {
        var payloads = new CloudEventPayloads(JSON, codec, true);
        byte[] data = codec.encode(OrderEvents.DESTINATION, OrderLifecycleAvroMapper.toAvro(AvroFixtures.sample()));
        var avroEvent = event(new CloudEventPayloads.Encoded("application/avro", data));
        var jsonEvent = event(new CloudEventPayloads.Encoded("application/json",
                JSON.writeValueAsBytes(AvroFixtures.sample())));

        assertThat(payloads.readOrderLifecycle(avroEvent)).isEqualTo(AvroFixtures.sample());
        assertThat(payloads.readOrderLifecycle(jsonEvent)).isEqualTo(AvroFixtures.sample());
    }

    @Test
    @DisplayName("without a registry: Avro cannot be read or written, with a message that names the setting")
    void withoutRegistry() {
        var jsonOnly = new CloudEventPayloads(JSON, null, false);
        var avro = event(new CloudEventPayloads(JSON, codec, true).writeShipment(SHIPMENT));

        assertThatThrownBy(() -> jsonOnly.readShipment(avro)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema-registry-url");
        assertThatThrownBy(() -> new CloudEventPayloads(JSON, null, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema-registry-url");
    }
}
