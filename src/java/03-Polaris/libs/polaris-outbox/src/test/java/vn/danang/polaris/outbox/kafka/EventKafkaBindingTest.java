package vn.danang.polaris.outbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;

import vn.danang.polaris.outbox.trace.W3cTraceContext;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

class EventKafkaBindingTest {

    private static final UUID ID = UUID.fromString("6f1b1c0e-52a4-4f43-9a8e-1d2b3c4d5e6f");

    private static OutgoingEvent event(W3cTraceContext trace) {
        return new OutgoingEvent(ID, "vn.danang.polaris.order.placed.v1", "/polaris/order",
                Instant.parse("2026-09-29T10:15:30.123Z"), "polaris.order.lifecycle", "ORD-1", "{\"orderNumber\":\"ORD-1\"}",
                trace);
    }

    private static Map<String, String> headers(ProducerRecord<?, ?> record) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Header h : record.headers()) {
            map.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
        }
        return map;
    }

    @Test
    void jsonEncoder_keepsThePayloadAsRecorded_andCarriesPlainEventHeaders() throws Exception {
        ProducerRecord<String, byte[]> record = EventKafkaBinding.toRecord(event(W3cTraceContext.NONE), EventValueEncoder.JSON);

        assertThat(record.topic()).isEqualTo("polaris.order.lifecycle");
        assertThat(record.key()).isEqualTo("ORD-1");
        assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo("{\"orderNumber\":\"ORD-1\"}");
        assertThat(headers(record)).containsOnly(
                Map.entry("event-id", ID.toString()),
                Map.entry("event-type", "vn.danang.polaris.order.placed.v1"),
                Map.entry("event-source", "/polaris/order"),
                Map.entry("event-time", "2026-09-29T10:15:30.123Z"),
                Map.entry("content-type", "application/json"));
    }

    @Test
    void aCustomEncoder_decidesTheValueBytesAndContentType() throws Exception {
        EventValueEncoder avroLike = new EventValueEncoder() {
            @Override
            public byte[] encode(OutgoingEvent event) {
                return new byte[] { 0, 0, 0, 0, 1, 42 };
            }

            @Override
            public String contentType() {
                return "application/avro";
            }
        };

        ProducerRecord<String, byte[]> record = EventKafkaBinding.toRecord(event(W3cTraceContext.NONE), avroLike);

        assertThat(record.value()).containsExactly(0, 0, 0, 0, 1, 42);
        assertThat(headers(record)).containsEntry("content-type", "application/avro");
    }

    @Test
    void anEncoderFailure_propagates_soTheRelayRetries() {
        EventValueEncoder failing = new EventValueEncoder() {
            @Override
            public byte[] encode(OutgoingEvent event) {
                throw new IllegalStateException("registry unreachable");
            }

            @Override
            public String contentType() {
                return "application/avro";
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EventKafkaBinding.toRecord(event(W3cTraceContext.NONE), failing))
                .isInstanceOf(IllegalStateException.class).hasMessage("registry unreachable");
    }

    @Test
    void traceContext_isCarriedInW3cHeaders() throws Exception {
        var trace = new W3cTraceContext("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "polaris=t1");

        Map<String, String> headers = headers(EventKafkaBinding.toRecord(event(trace), EventValueEncoder.JSON));

        assertThat(headers).containsEntry("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .containsEntry("tracestate", "polaris=t1");
    }
}
