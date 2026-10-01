package vn.danang.polaris.events.avro.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code polaris.events.*}. {@code format} selects what this service <b>writes</b>; what it reads follows each event's
 * content type. The registry URL is optional for {@code json}: when set, the service can still read Avro events.
 *
 * @param format whether outgoing event payloads are {@code JSON} (ADR-0019, default) or {@code AVRO} (ADR-0021)
 */
@ConfigurationProperties("polaris.events")
public record AvroEventsProperties(@DefaultValue("json") Format format, @DefaultValue Avro avro) {

    public enum Format {
        JSON, AVRO
    }

    /**
     * @param schemaRegistryUrl Schema Registry base URL; absent means Avro is not available
     * @param autoRegister      whether this service may register schemas. Default {@code false}: schemas are registered
     *                          by the schema-init gate, so only reviewed schemas exist (see the experiment notes)
     */
    public record Avro(String schemaRegistryUrl, @DefaultValue("false") boolean autoRegister) {
    }
}
