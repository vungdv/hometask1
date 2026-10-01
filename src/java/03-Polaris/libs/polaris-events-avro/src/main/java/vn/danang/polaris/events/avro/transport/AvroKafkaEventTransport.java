package vn.danang.polaris.events.avro.transport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.outbox.kafka.KafkaEventTransport;
import vn.danang.polaris.outbox.transport.EventTransport;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Avro implementation of the outbox transport port, next to the existing CloudEvents {@link KafkaEventTransport}
 * (which is unchanged). The destination picks an {@link AvroMessageMapper}, which builds the Avro message (metadata
 * plus typed payload); the {@link AvroEventCodec} frames it for the Schema Registry; the record is keyed by the
 * aggregate id and carries only the W3C trace headers, because the metadata lives inside the message.
 *
 * <p>The delivery guarantees are the existing transport's: build the template over
 * {@link KafkaEventTransport#producerOverrides(Duration)} ({@code acks=all}, idempotence, bounded sends), and
 * {@link #send} returns only after the broker acknowledged the record. Anything thrown (unknown destination, payload
 * that does not map, registry unreachable, schema refused with 409, broker timeout) is a failed attempt that the
 * relay retries with its backoff. The registry is therefore on the relay's path.
 */
public class AvroKafkaEventTransport implements EventTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AvroKafkaEventTransport.class);

    private final KafkaTemplate<String, byte[]> template;
    private final KafkaAdmin admin;
    private final Duration sendTimeout;
    private final AvroEventCodec codec;
    private final Map<String, AvroMessageMapper> mappers;
    private volatile boolean topicsProvisioned;

    /**
     * @param template    a template over a producer configured with {@link KafkaEventTransport#producerOverrides}
     * @param admin       provisions the declared topics, or {@code null} to leave provisioning to others
     * @param sendTimeout upper bound of each phase of a send
     * @param codec       frames messages for the Schema Registry
     * @param mappers     one per destination this transport can deliver
     */
    public AvroKafkaEventTransport(KafkaTemplate<String, byte[]> template, KafkaAdmin admin, Duration sendTimeout,
            AvroEventCodec codec, List<AvroMessageMapper> mappers) {
        this.template = Objects.requireNonNull(template, "template");
        this.admin = admin;
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.mappers = mappers.stream().collect(Collectors.toUnmodifiableMap(AvroMessageMapper::destination,
                Function.identity()));
        this.topicsProvisioned = admin == null;
    }

    @Override
    public void send(OutgoingEvent event) throws Exception {
        AvroMessageMapper mapper = mappers.get(event.destination());
        if (mapper == null) {
            throw new IllegalArgumentException("No Avro message mapper for destination " + event.destination());
        }
        provisionTopicsOnce();
        byte[] value = codec.encode(event.destination(), mapper.toMessage(event));
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(event.destination(), null, event.key(), value);
        event.traceHeaders().forEach((name, header) -> record.headers().add(name, header.getBytes(StandardCharsets.UTF_8)));
        RecordMetadata metadata = template.send(record)
                .get(sendTimeout.toMillis() + 1_000, TimeUnit.MILLISECONDS)
                .getRecordMetadata();
        log.debug("Avro record sent event_id={} event_type={} key={} topic={} partition={} offset={}",
                event.id(), event.type(), event.key(), metadata.topic(), metadata.partition(), metadata.offset());
    }

    private void provisionTopicsOnce() {
        if (!topicsProvisioned) {
            // Throws while the broker is unavailable: a failed attempt, retried with the relay's backoff.
            admin.initialize();
            topicsProvisioned = true;
        }
    }

    @Override
    public void close() {
        template.getProducerFactory().reset();
    }
}
