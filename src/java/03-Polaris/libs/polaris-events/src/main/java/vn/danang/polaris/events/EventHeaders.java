package vn.danang.polaris.events;

/**
 * Kafka record headers that carry an event's metadata now that CloudEvents is gone (avro experiment).
 * They replace the {@code ce_id}, {@code ce_type}, {@code ce_source} and {@code ce_time} headers one for one, so
 * consumers can still route and deduplicate without decoding the Avro value. W3C {@code traceparent} and
 * {@code tracestate} are unchanged.
 */
public final class EventHeaders {

    /** Event id, stable across delivery attempts (the old {@code ce_id}). */
    public static final String ID = "event-id";
    /** Event type such as {@code vn.danang.polaris.order.placed.v1} (the old {@code ce_type}). */
    public static final String TYPE = "event-type";
    /** Producing context (the old {@code ce_source}). */
    public static final String SOURCE = "event-source";
    /** When the event was recorded, ISO-8601 UTC (the old {@code ce_time}). */
    public static final String TIME = "event-time";
    /** Media type of the value: {@value #AVRO_CONTENT_TYPE}. */
    public static final String CONTENT_TYPE = "content-type";

    public static final String AVRO_CONTENT_TYPE = "application/avro";

    private EventHeaders() {
    }
}
