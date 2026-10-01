package vn.danang.polaris.events.avro;

import java.util.Map;

import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.util.ClassSecurityValidator;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import vn.danang.polaris.events.avro.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.avro.fulfilment.ShipmentStep;
import vn.danang.polaris.events.avro.order.Customer;
import vn.danang.polaris.events.avro.order.Item;
import vn.danang.polaris.events.avro.order.OrderLifecycleEvent;
import vn.danang.polaris.events.avro.order.OrderMilestone;

/**
 * Encodes and decodes the Avro event values in the Confluent wire format (magic byte, schema id, Avro binary),
 * resolving schemas through the Schema Registry. Kafka values stay {@code byte[]} everywhere, so templates, listener
 * containers and the outbox relay are unchanged: only the bytes are different.
 *
 * <p>Avro 1.12 refuses to load classes by name unless they are trusted, so this class trusts the generated contract
 * classes once. Without it, decoding fails at runtime with a {@code SecurityException}.
 */
public final class AvroEventCodec implements AutoCloseable {

    static {
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(),
                ClassSecurityValidator.builder()
                        .add(OrderLifecycleEvent.class).add(Customer.class).add(Item.class).add(OrderMilestone.class)
                        .add(ShipmentEvent.class).add(ShipmentStep.class)
                        .build()));
    }

    private final KafkaAvroSerializer serializer = new KafkaAvroSerializer();
    private final KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer();

    /**
     * @param registryUrl  Schema Registry URL ({@code mock://scope} for an in-memory registry in tests)
     * @param autoRegister whether a producer may register the schema it writes. Convenient in dev; production should
     *                     register from CI and set this to {@code false} (see the experiment notes)
     */
    public AvroEventCodec(String registryUrl, boolean autoRegister) {
        Map<String, Object> config = Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, registryUrl,
                AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, autoRegister,
                KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        serializer.configure(config, false);
        deserializer.configure(config, false);
    }

    /** Registry-framed Avro bytes for {@code record}, registered under subject {@code <topic>-value}. */
    public byte[] encode(String topic, SpecificRecord record) {
        return serializer.serialize(topic, record);
    }

    /** The record written with any registered schema version, read as the generated class of that schema. */
    @SuppressWarnings("unchecked")
    public <T extends SpecificRecord> T decode(String topic, byte[] value) {
        return (T) deserializer.deserialize(topic, value);
    }

    @Override
    public void close() {
        serializer.close();
        deserializer.close();
    }
}
