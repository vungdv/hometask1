package vn.danang.polaris.outbox.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.producer.ProducerRecord;

import vn.danang.polaris.events.EventHeaders;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Maps an outbox event to a Kafka record (avro experiment, replacing the CloudEvents binding of ADR-0019):
 * <ul>
 * <li>metadata travels in plain headers ({@link EventHeaders}), so consumers can route and deduplicate without
 * decoding the value;</li>
 * <li>the value is whatever the {@link EventValueEncoder} produces, with no serializer type headers;</li>
 * <li>the record key is the aggregate id, so a key's events share a partition and keep their order;</li>
 * <li>the W3C {@code traceparent}/{@code tracestate} handed over by the relay are added as headers (TR-B4).</li>
 * </ul>
 */
public final class EventKafkaBinding {

    private EventKafkaBinding() {
    }

    /** The record for {@code event}: topic = destination, key = aggregate id. */
    public static ProducerRecord<String, byte[]> toRecord(OutgoingEvent event, EventValueEncoder encoder)
            throws Exception {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(event.destination(), null, event.key(),
                encoder.encode(event));
        header(record, EventHeaders.ID, event.id().toString());
        header(record, EventHeaders.TYPE, event.type());
        header(record, EventHeaders.SOURCE, event.source());
        header(record, EventHeaders.TIME, event.time().toString());
        header(record, EventHeaders.CONTENT_TYPE, encoder.contentType());
        event.traceHeaders().forEach((name, value) -> {
            record.headers().remove(name);
            header(record, name, value);
        });
        return record;
    }

    private static void header(ProducerRecord<String, byte[]> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
