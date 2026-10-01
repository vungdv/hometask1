package vn.danang.polaris.events.avro;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import vn.danang.polaris.events.EventHeaders;

/** The event metadata headers of a consumed record (replaces reading a CloudEvent). Absent headers are {@code null}. */
public record EventMeta(String id, String type, String source, String time) {

    public static EventMeta of(Headers headers) {
        return new EventMeta(text(headers, EventHeaders.ID), text(headers, EventHeaders.TYPE),
                text(headers, EventHeaders.SOURCE), text(headers, EventHeaders.TIME));
    }

    private static String text(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
