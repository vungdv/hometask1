package vn.danang.polaris.events.avro.transport;

import org.apache.avro.specific.SpecificRecord;

import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Adapter from the outbox's {@link OutgoingEvent} to the Avro <b>payload</b> of one destination: the generated record
 * that becomes the CloudEvent {@code data}. The CloudEvents attributes (id, type, source, time) stay in the {@code ce_*}
 * headers; only the payload is typed by Avro and governed by the Schema Registry. One implementation per destination.
 */
public interface AvroPayloadMapper {

    /** The destination (topic) whose payload this mapper builds. */
    String destination();

    /** The Avro payload for {@code event}. A payload that cannot be mapped throws, which fails the attempt. */
    SpecificRecord toPayload(OutgoingEvent event) throws Exception;
}
