package vn.danang.polaris.order.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.AvroEventCodec;
import vn.danang.polaris.outbox.kafka.EventValueEncoder;

/** Avro event encoding for Order (avro experiment): the Schema Registry codec and the outbox value encoder. */
@Configuration(proxyBeanMethods = false)
class AvroEventsConfiguration {

    @Bean(destroyMethod = "close")
    AvroEventCodec avroEventCodec(@Value("${polaris.avro.schema-registry-url:http://localhost:8081}") String registryUrl,
            @Value("${polaris.avro.auto-register-schemas:true}") boolean autoRegister) {
        return new AvroEventCodec(registryUrl, autoRegister);
    }

    @Bean
    EventValueEncoder orderEventValueEncoder(AvroEventCodec codec, JsonMapper mapper) {
        return new OrderAvroValueEncoder(codec, mapper);
    }
}
