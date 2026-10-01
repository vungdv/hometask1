package vn.danang.polaris.events.avro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;

import vn.danang.polaris.events.order.OrderLifecycleEvent;
import vn.danang.polaris.events.order.OrderMilestone;

final class AvroFixtures {

    static final Schema V1 = vn.danang.polaris.events.avro.order.OrderLifecycleEvent.getClassSchema();

    private AvroFixtures() {
    }

    static Schema load(String path) {
        try (InputStream in = AvroFixtures.class.getResourceAsStream(path)) {
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Schema evolution(String name) {
        return load("/avro/evolution/" + name + ".avsc");
    }

    /** The sample as a GenericRecord, via bytes (SpecificData.deepCopy trips Avro 1.12's class-trust check). */
    static GenericRecord sampleAsGeneric() {
        var avro = vn.danang.polaris.events.avro.order.OrderLifecycleAvroMapper.toAvro(sample());
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var enc = EncoderFactory.get().binaryEncoder(out, null);
            new org.apache.avro.specific.SpecificDatumWriter<>(avro.getSchema()).write(avro, enc);
            enc.flush();
            return decode(out.toByteArray(), V1, V1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static OrderLifecycleEvent sample() {
        return new OrderLifecycleEvent("ORD-10042", OrderMilestone.CONFIRMED, Instant.parse("2026-09-28T09:15:02.311Z"),
                new OrderLifecycleEvent.Customer(1L, "Alice Tran", "alice.tran@example.com"),
                List.of(new OrderLifecycleEvent.Item("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.90")),
                        new OrderLifecycleEvent.Item("NG-CHARGER-01", "Fast Charger", 2, new BigDecimal("24.90"))),
                new BigDecimal("99.70"), "USD", "partner-north");
    }

    /** Binary-encodes {@code record} with its own (writer) schema. */
    static byte[] encode(GenericRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var enc = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<GenericRecord>(record.getSchema()).write(record, enc);
            enc.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Schema resolution: bytes written with {@code writer}, read as {@code reader}. */
    static GenericRecord decode(byte[] bytes, Schema writer, Schema reader) {
        try {
            return new GenericDatumReader<GenericRecord>(writer, reader)
                    .read(null, DecoderFactory.get().binaryDecoder(bytes, null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
