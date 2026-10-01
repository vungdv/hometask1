package vn.danang.polaris.events.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.danang.polaris.events.avro.AvroFixtures.V1;
import static vn.danang.polaris.events.avro.AvroFixtures.evolution;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper;
import vn.danang.polaris.events.order.OrderLifecycleEvent;

/** Library-level facts about Avro evolution, independent of any registry. */
@DisplayName("Avro schema evolution (reader/writer resolution)")
class AvroSchemaEvolutionTest {

    private static SchemaCompatibilityType canRead(Schema reader, Schema writer) {
        return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getType();
    }

    @Test
    @DisplayName("additive v2 is BACKWARD and FORWARD compatible (new field/symbol/sub-field all carry defaults)")
    void additiveIsFull() {
        Schema v2 = evolution("order-lifecycle-v2-additive");

        assertThat(canRead(v2, V1)).isEqualTo(SchemaCompatibilityType.COMPATIBLE); // new reader, old data
        assertThat(canRead(V1, v2)).isEqualTo(SchemaCompatibilityType.COMPATIBLE); // old reader, new data
    }

    @Test
    @DisplayName("required field without default: BACKWARD breaks, FORWARD still holds")
    void requiredFieldAdded() {
        Schema bad = evolution("order-lifecycle-bad-required-field");

        assertThat(canRead(bad, V1)).isEqualTo(SchemaCompatibilityType.INCOMPATIBLE);
        assertThat(canRead(V1, bad)).isEqualTo(SchemaCompatibilityType.COMPATIBLE);
    }

    @Test
    @DisplayName("removing a field that has no default: FORWARD breaks (old reader cannot fill it)")
    void requiredFieldRemoved() {
        Schema bad = evolution("order-lifecycle-bad-removed-required-field");

        assertThat(canRead(V1, bad)).isEqualTo(SchemaCompatibilityType.INCOMPATIBLE);
        assertThat(canRead(bad, V1)).isEqualTo(SchemaCompatibilityType.COMPATIBLE);
    }

    @Test
    @DisplayName("type change string -> int is incompatible both ways; int -> long is a legal promotion one way")
    void typeChanges() {
        Schema bad = evolution("order-lifecycle-bad-type-change");
        Schema widened = evolution("order-lifecycle-v2-int-to-long");

        assertThat(canRead(bad, V1)).isEqualTo(SchemaCompatibilityType.INCOMPATIBLE);
        assertThat(canRead(V1, bad)).isEqualTo(SchemaCompatibilityType.INCOMPATIBLE);
        assertThat(canRead(widened, V1)).isEqualTo(SchemaCompatibilityType.COMPATIBLE);
        assertThat(canRead(V1, widened)).isEqualTo(SchemaCompatibilityType.INCOMPATIBLE); // long cannot narrow to int
    }

    @Test
    @DisplayName("adding an enum symbol is FORWARD-safe only because the enum declares a default")
    void enumSymbols() {
        Schema v1NoDefault = evolution("order-lifecycle-v1-enum-no-default");
        Schema v2NoDefault = evolution("order-lifecycle-v2-enum-no-default");
        Schema v2 = evolution("order-lifecycle-v2-additive");

        assertThat(canRead(v1NoDefault, v2NoDefault)).isEqualTo(SchemaCompatibilityType.INCOMPATIBLE);
        assertThat(canRead(V1, v2)).isEqualTo(SchemaCompatibilityType.COMPATIBLE);
    }

    @Test
    @DisplayName("rename without alias is still 'compatible' when the field has a default, and silently loses the value")
    void rename() {
        Schema aliased = evolution("order-lifecycle-v2-renamed-with-alias");
        Schema plain = evolution("order-lifecycle-bad-renamed-no-alias");
        GenericRecord v1Record = AvroFixtures.sampleAsGeneric();
        byte[] bytes = AvroFixtures.encode(v1Record);

        assertThat(canRead(aliased, V1)).isEqualTo(SchemaCompatibilityType.COMPATIBLE);
        assertThat(canRead(plain, V1)).isEqualTo(SchemaCompatibilityType.COMPATIBLE); // the compatibility check passes...

        assertThat(AvroFixtures.decode(bytes, V1, aliased).get("partnerId").toString()).isEqualTo("partner-north");
        assertThat(AvroFixtures.decode(bytes, V1, plain).get("partnerId")).isNull(); // ...but the data is gone
    }

    @Test
    @DisplayName("v1 bytes read with the v2 schema fill defaults; v2 bytes read with v1 silently DROP the new fields")
    void dataResolution() {
        Schema v2 = evolution("order-lifecycle-v2-additive");
        GenericRecord v1Record = AvroFixtures.sampleAsGeneric();

        GenericRecord asV2 = AvroFixtures.decode(AvroFixtures.encode(v1Record), V1, v2);
        assertThat(asV2.get("shipmentId")).isNull();
        assertThat(((GenericRecord) asV2.get("customer")).get("tier").toString()).isEqualTo("STANDARD");

        asV2.put("shipmentId", "SHP-1");
        GenericRecord asV1 = AvroFixtures.decode(AvroFixtures.encode(asV2), v2, V1);
        assertThat(asV1.getSchema().getField("shipmentId")).isNull(); // v1 consumer never sees it: no error, no signal
    }

    @Test
    @DisplayName("mapper round-trips the JSON contract record; decimal scale is kept, sub-millisecond time is not")
    void mapperRoundTrip() {
        OrderLifecycleEvent event = AvroFixtures.sample();

        OrderLifecycleEvent back = OrderLifecycleAvroMapper.fromAvro(OrderLifecycleAvroMapper.toAvro(event));

        assertThat(back).isEqualTo(event);
        assertThat(back.totalAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("size: Avro binary vs the JSON payload used today")
    void sizeComparison() {
        OrderLifecycleEvent event = AvroFixtures.sample();
        int json = JsonMapper.builder().build().writeValueAsBytes(event).length;
        int bin = AvroFixtures.encode(AvroFixtures.sampleAsGeneric()).length;

        System.out.printf("PAYLOAD SIZE json=%d avro=%d (%.0f%% of json; +5 bytes registry framing)%n", json, bin,
                100.0 * bin / json);
        assertThat(bin).isLessThan(json);
    }

    @Test
    @DisplayName("garbage bytes are rejected at decode, not silently accepted")
    void garbage() {
        assertThatThrownBy(() -> AvroFixtures.decode(new byte[] {1, 2, 3}, V1, V1)).isInstanceOf(RuntimeException.class);
    }
}
