package vn.danang.polaris.events.avro.transport;

import org.apache.avro.specific.SpecificRecord;

import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Adapter from the outbox's {@link OutgoingEvent} to the Avro <b>message</b> of one destination: the generated record
 * that is the whole Kafka value, holding the event metadata (id, type, source, time) and the typed payload. One
 * implementation per destination, so the Avro schema of a topic is a message schema owned by that topic's context.
 */
public interface AvroMessageMapper {

    /** The destination (topic) whose message this mapper builds. */
    String destination();

    /** The Avro message for {@code event}. A payload that cannot be mapped throws, which fails the attempt. */
    SpecificRecord toMessage(OutgoingEvent event) throws Exception;
}
