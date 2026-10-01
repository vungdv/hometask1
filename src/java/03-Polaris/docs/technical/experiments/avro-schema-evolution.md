# Experiment: Avro + Schema Registry + schema evolution for Polaris Kafka events

* **Status:** Experiment, not adopted. Branch `experiment/avro-schema-evolution`. Nothing in `apps/` or `polaris-outbox` is changed.
* **Date:** 2026-10-01
* **Question:** Should the Kafka event payloads move from JSON (ADR-0019) to Avro with a Schema Registry, and what do we gain and pay?
* **Baseline:** [ADR-0019](../decisions/0019-kafka-and-cloudevents-binding.md): CloudEvents 1.0 binary mode, JSON value, lenient consumers, no registry.

Evidence labels used below: **[measured]** observed in this experiment's tests; **[doc]** known Avro/Confluent behaviour that was not re-tested here.

---

## 1. What was built

New, isolated module `libs/polaris-events-avro` (added to the parent reactor, depended on by nobody).

| Piece | Where |
|:--|:--|
| Avro twin of the order lifecycle payload (schema v1) | `src/main/avro/OrderLifecycleEvent.avsc` |
| Java classes generated from it by `avro-maven-plugin` | `target/generated-sources` |
| Hand-written mapper JSON-contract record <-> Avro class | `OrderLifecycleAvroMapper` |
| Evolution scenarios as `.avsc` files (additive, bad required field, removed field, type change, enum, rename, int->long) | `src/test/resources/avro/evolution/` |
| Library-level evolution tests (no registry) | `AvroSchemaEvolutionTest` (10 tests) |
| End to end test: real Kafka (`apache/kafka:3.9.1`) + real Confluent Schema Registry (`cp-schema-registry:8.0.0`) via Testcontainers | `RegistryKafkaIntegrationTest` (4 tests) |

Schema design choices: money is `bytes` + `decimal(12,2)` (JSON used a string), time is `long` + `timestamp-millis`, `status` is an enum with `default: UNKNOWN`, every optional field is a `["null", T]` union with `default: null`, `items` defaults to `[]`.

How the record looks on Kafka in the experiment (still the ADR-0019 binding, only the value changes):

* `ce_*` headers and the record key are unchanged; `content-type` becomes `application/avro`.
* Value = Confluent wire format: magic byte `0x00` + 4-byte schema id + Avro binary.
* The CloudEvents SDK (`KafkaMessageFactory...writeBinary`) carried the Avro bytes without any change, because binary mode treats `data` as opaque bytes. **[measured]**

Run it: `mvn -pl libs/polaris-events-avro test` (needs Docker; the first run pulls the registry image, which is large).

## 2. Results

### 2.1 Registry behaviour (integration test) **[measured]**

| Scenario | Result |
|:--|:--|
| Subject set to `BACKWARD_TRANSITIVE`, register v1 | accepted, schema id 1 |
| Register additive v2 (new optional field, new enum symbol, new defaulted sub-field) | accepted as version 2 |
| A v1 reader decodes a record written with v2 (writer schema fetched from the registry by id) | works |
| Register a schema with a new required field (no default) | **rejected, HTTP 409**; `testCompatibility` returns false |
| Same kind of breaking change on a subject configured `NONE` | **accepted**: the guard is per-subject configuration, not a property of the data |

### 2.2 Compatibility matrix (library level) **[measured]**

"Backward" = new schema reads old data. "Forward" = old schema reads new data.

| Change | Backward | Forward |
|:--|:--|:--|
| Add optional field / sub-field with default | OK | OK |
| Add required field, no default | **breaks** | OK |
| Remove field that has no default | OK | **breaks** |
| `string` -> `int` | **breaks** | **breaks** |
| `int` -> `long` | OK (promotion) | **breaks** |
| Add enum symbol, enum has a `default` | OK | OK |
| Add enum symbol, enum has no `default` | OK | **breaks** |
| Rename a defaulted field, with alias | OK, value kept | n/a |
| Rename a defaulted field, **no alias** | reported **compatible** | the value is silently lost |

### 2.3 Data-level surprises **[measured]**

* A v1 consumer reading a v2 record **drops the new fields silently**: no error, no metric. That is the same outcome as today's `ignoreUnknown`, but it is now a designed property rather than an accident, and it is the reason "forward compatible" is not the same as "the consumer is correct".
* A rename without an alias passes the compatibility check but loses the data (see matrix). The registry cannot tell a rename from "drop a field and add a new defaulted one".
* Money keeps scale (`49.90` stays `49.90`) through the generated `BigDecimal`. Time is truncated to **milliseconds** (`Instant` carries nanoseconds); the mapper truncates explicitly, and the round-trip test equals only because the sample is millisecond precision.

### 2.4 Size **[measured]** (one realistic `order.confirmed` payload, 2 items)

| Representation | Bytes |
|:--|:--|
| JSON payload used today | 410 |
| Avro binary | 150 (37%) |
| Avro in the registry wire format | 155 |

One sample, so treat the ratio as indicative. Gain grows with repeated field names (items) and shrinks with long string values; the registry has no per-record lookup cost after the schema id is cached **[doc]**.

## 3. Advantages (for Polaris)

1. **Compatibility is enforced, not conventional.** Today TR-X6 relies on every consumer being lenient. With a registry in `BACKWARD_TRANSITIVE` (or `FULL_TRANSITIVE`) a breaking schema fails at registration/CI with a 409, before any event is produced. **[measured]**
2. **Explicit evolution rules.** The table in 2.2 is mechanical and checkable with `SchemaCompatibility` in plain unit tests, no broker needed. **[measured]**
3. **Smaller records.** About 63% less payload for the sample, which reduces replication traffic on the 3-node cluster. **[measured]**
4. **Better types than JSON.** Decimals with fixed scale and precision, timestamps, enums with a declared default. The JSON contract needs `@JsonFormat(STRING)` workarounds for money today. **[measured]**
5. **The CloudEvents binding survives.** Headers, key and tracing are unaffected; only `content-type` and the value change. **[measured]**
6. **Ecosystem fit** for Connect, ksqlDB, Flink and lake sinks, which read registry-framed Avro natively. **[doc]**
7. **A catalogue of contracts**: subjects and versions in one place, which supports AGENTS.md "traceable integration points". **[doc]**

## 4. Disadvantages and costs

1. **The outbox payload is JSON text.** `OutgoingEvent.payload` is a `String` stored as recorded (E2/E3). Avro needs either serialization at record time (payload column becomes binary/base64, schema id fixed at write time) or in the relay (the relay then needs the registry on its hot path). The experiment did not change the outbox; this is the largest unproven part. **[doc, from reading E3]**
2. **Registry is a new critical runtime dependency.** Producers need it to register/look up a schema id; consumers need it for any id not cached. It needs HA, auth, backup, metrics, alerts and a runbook, which is a whole slice of Principle 3 work on top of O6/O7. In the experiment the registry container also needed Kafka reachable and a long image pull. **[measured: setup; doc: HA needs]**
3. **Wire format is a vendor convention.** Magic byte + schema id is Confluent's, not a CloudEvents or Avro standard, which sits uneasily with Principle 1. CloudEvents has an Avro *format* spec but it is a different (structured-mode) design. **[doc]**
4. **Readability is lost.** `make kafka-tail`, `verify-kafka-events.sh` and the k6 checks read the value as JSON text today; they would all need an Avro-aware consumer. **[doc, from repo]**
5. **Tooling friction found while building it. [measured]**
   * `avro-maven-plugin` generates `ByteBuffer` for decimals unless `enableDecimalLogicalType=true`.
   * Avro 1.12 refuses to load generated classes by name unless the package is whitelisted via `org.apache.avro.SERIALIZABLE_PACKAGES`. Every producer and consumer JVM needs that property; forgetting it fails at runtime with a `SecurityException`, not at build time.
   * The Confluent serializer artifacts are not on Maven Central; the build needs `https://packages.confluent.io/maven/`.
   * Incremental builds kept stale generated sources until `clean`.
6. **Two representations to keep in sync.** The mapper is hand-written; a field added on one side and forgotten on the other fails only if a test covers it. Generating the Java contract from the `.avsc` (and dropping the Jackson records) would remove the duplication but changes `polaris-events` for every consumer. **[measured: mapper needed]**
7. **Evolution is not free.** Renames need aliases, type changes are mostly blocked, enums need a default from day one (it cannot be added later without breaking old readers), and the rename-without-alias hole in 2.3 means "compatible" does not imply "correct". **[measured]**
8. **Failure mode is wider.** A bad or unreachable schema id makes a record undecodable for every consumer at once. With the current no-DLT policy (ADR-0019 §4.1 item 4) that becomes a skipped-and-counted poison record per consumer. **[doc]**
9. **Compatibility is configuration.** A subject accidentally set to `NONE` accepts anything (2.1). The mode must be set by provisioning code and checked in CI, not by hand. **[measured]**

## 5. What this experiment does not cover

* Outbox/relay changes, consumer migration and a dual-format rollout.
* Registry HA, authentication, ACLs, retention of old versions, disaster recovery.
* Performance (serialization CPU, registry latency under load, schema-cache misses).
* Protobuf or JSON Schema alternatives, and CI-only compatibility checking without a runtime registry (for example Avro `SchemaCompatibility` on committed `.avsc` files, which this experiment shows works without any broker).

## 6. Take-aways

* Technically it works with the existing CloudEvents binding; the integration test proves the end to end path, including enforcement.
* The measurable gains are enforcement and size; the measurable costs are tooling friction and the unresolved outbox payload question.
* A cheaper middle path suggested by the results: keep JSON on the wire and run the same compatibility gate in CI on committed schemas, which gives most of advantage 1 and 2 without a runtime registry.
* If Avro is pursued, the next vertical slice is the outbox: decide where serialization happens and what the payload column stores, then write an ADR superseding ADR-0019 §3.1.

## 7. Phase 2: CloudEvents replaced completely

Requested follow-up: use the Avro mapper to replace CloudEvents on both topics, in this branch. Decisions taken: event metadata moves to plain `event-id`, `event-type`, `event-source`, `event-time` headers; both `polaris.order.lifecycle` and `polaris.fulfilment.shipments` move to Avro. The decision record is [ADR-0021](../decisions/0021-avro-schema-registry-vs-cloudevents.md).

What changed:

* `polaris-outbox`: `CloudEventsKafkaBinding` removed; new `EventKafkaBinding` and an `EventValueEncoder` port. The outbox still stores JSON and the relay is unchanged. The `cloudevents-kafka` dependency is gone from the parent, the outbox and both apps.
* `polaris-events-avro`: shipment schema and mapper, `AvroEventCodec` (Confluent wire format, trusts the generated classes once), `EventMeta` (reads the headers).
* `apps/polaris`: `OrderAvroValueEncoder` encodes the recorded JSON at hand-off; `ShipmentReportHandler` decodes Avro.
* `apps/polaris-fulfilment-emulator`: `OfferHandler` decodes Avro, `ShipmentPublisher` encodes it.
* Compose: `schema-registry` service (`BACKWARD_TRANSITIVE` default), apps wait for it.
* `tests/e2e/verify-kafka-events.sh` reads through `kafka-avro-console-consumer` and matches `event-type`.

Measured: the integration tests listed in ADR-0021 section 6 pass. Not verified: the compose stack and the updated e2e script were not run.

Findings specific to this phase:

* Replacing CloudEvents touched the outbox, two apps, six integration tests, the e2e script and compose, and removed no registry cost. Keeping CloudEvents (option C in the ADR) would have avoided all of it.
* Tests that asserted on the JSON value text (`"partnerId":"..."`) cannot work on Avro bytes; each had to decode the record instead.
* A registry outage stops delivery of every event (the encoder runs inside the relay's send), where before only a broker outage did.
* The `event-*` header names are a Polaris convention with no standard behind them.

