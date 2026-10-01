package vn.danang.polaris.outbox.kafka;

import java.nio.charset.StandardCharsets;

import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Turns a recorded event into the bytes of the Kafka record value. The outbox stores the payload as JSON text; this
 * is the single place where it can become something else (Avro with a Schema Registry, for instance). A failure here
 * is a failed delivery attempt, which the relay retries with its backoff, so an unreachable registry holds events in
 * {@code PENDING} like an unreachable broker does.
 */
public interface EventValueEncoder {

    /** The recorded JSON, unchanged. */
    EventValueEncoder JSON = new EventValueEncoder() {
        @Override
        public byte[] encode(OutgoingEvent event) {
            return event.payload().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String contentType() {
            return OutgoingEvent.DATA_CONTENT_TYPE;
        }
    };

    byte[] encode(OutgoingEvent event) throws Exception;

    /** Media type put in the {@code content-type} header. */
    String contentType();
}
