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

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.kafka.KafkaMessageFactory;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.events.avro.cloudevents.CloudEventPayloads;
import vn.danang.polaris.outbox.kafka.CloudEventsKafkaBinding;
import vn.danang.polaris.outbox.kafka.KafkaEventTransport;
import vn.danang.polaris.outbox.transport.EventTransport;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Avro implementation of the outbox transport port, next to the existing {@link KafkaEventTransport} (unchanged).
 * The record is still a CloudEvents 1.0 binary-mode record: {@code ce_*} headers, key = aggregate id, W3C trace
 * headers. Only the {@code data} differs: the destination picks an {@link AvroPayloadMapper}, and the
 * {@link AvroEventCodec} frames the typed payload for the Schema Registry ({@code content-type: application/avro}).
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
    private final Map<String, AvroPayloadMapper> mappers;
    private volatile boolean topicsProvisioned;

    /**
     * @param template    a template over a producer configured with {@link KafkaEventTransport#producerOverrides}
     * @param admin       provisions the declared topics, or {@code null} to leave provisioning to others
     * @param sendTimeout upper bound of each phase of a send
     * @param codec       frames messages for the Schema Registry
     * @param mappers     one per destination this transport can deliver
     */
    public AvroKafkaEventTransport(KafkaTemplate<String, byte[]> template, KafkaAdmin admin, Duration sendTimeout,
            AvroEventCodec codec, List<AvroPayloadMapper> mappers) {
        this.template = Objects.requireNonNull(template, "template");
        this.admin = admin;
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.mappers = mappers.stream().collect(Collectors.toUnmodifiableMap(AvroPayloadMapper::destination,
                Function.identity()));
        this.topicsProvisioned = admin == null;
    }

    @Override
    public void send(OutgoingEvent event) throws Exception {
        AvroPayloadMapper mapper = mappers.get(event.destination());
        if (mapper == null) {
            throw new IllegalArgumentException("No Avro message mapper for destination " + event.destination());
        }
        provisionTopicsOnce();
        byte[] data = codec.encode(event.destination(), mapper.toPayload(event));
        CloudEvent cloudEvent = CloudEventBuilder.v1(CloudEventsKafkaBinding.toCloudEvent(event))
                .withDataContentType(CloudEventPayloads.AVRO_CONTENT_TYPE)
                .withData(data)
                .build();
        ProducerRecord<String, byte[]> record = KafkaMessageFactory
                .createWriter(event.destination(), event.key())
                .writeBinary(cloudEvent);
        event.traceHeaders().forEach((name, header) -> {
            record.headers().remove(name);
            record.headers().add(name, header.getBytes(StandardCharsets.UTF_8));
        });
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
