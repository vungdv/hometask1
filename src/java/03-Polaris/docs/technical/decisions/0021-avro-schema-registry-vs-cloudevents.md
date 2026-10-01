# ADR-0021: Avro + Schema Registry versus CloudEvents JSON for Kafka events

* **Status:** Proposed. Implemented on branch `experiment/avro-schema-evolution` only; **not adopted**. ADR-0019 stays in force on `main` until this is accepted.
* **Date:** 2026-10-01
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Technical Story:** Evaluate whether Kafka event payloads should move from JSON in CloudEvents binary mode to Avro governed by a Schema Registry, and what replaces CloudEvents if they do.
* **Would supersede:** [ADR-0019](0019-kafka-and-cloudevents-binding.md) §3.1 (record layout) and the consumer rule that unknown fields are ignored (§4.1 item 2). Builds on [ADR-0018](0018-transactional-outbox-for-integration-events.md).
* **Evidence:** [experiment notes](../experiments/avro-schema-evolution.md), module `libs/polaris-events-avro`, and the integration tests named in section 6.

---

## 1. Context and Problem Statement

ADR-0019 puts every event on Kafka as a CloudEvents 1.0 record in binary mode: `ce_*` headers, a JSON value, key = aggregate id. Compatibility is a convention: consumers ignore unknown types and fields, and a breaking change means a new `.vN` type. Nothing checks that a producer keeps the convention.

Two separate questions were bundled in the experiment, and they have different answers:

1. **Payload format and governance:** JSON by convention, or Avro with a registry that enforces compatibility?
2. **Envelope:** if the payload is Avro, is the CloudEvents envelope kept or dropped?

The experiment answered question 2 by **dropping CloudEvents completely** (as requested), to see what it costs. This ADR records both questions so the cheaper options are not lost.

## 2. Decision Drivers

* **Standards first (AGENTS.md Principle 1):** prefer established specifications over bespoke ones.
* **Enforced contracts:** a breaking change should fail before an event is produced (TR-X6).
* **Walkable architecture and operability (Principle 3):** every runtime dependency needs observability, a runbook and access control.
* **Minimal change (Principle 2):** the outbox (ADR-0018) and its relay are the most heavily tested part of the system.
* **Debuggability:** `make kafka-tail`, `verify-kafka-events.sh` and k6 read events as text today.
* **Scale of the problem:** two topics, one producer and one consumer per topic, all in one repository.

## 3. Considered Options

| # | Option | Payload | Envelope | Registry |
|:--|:--|:--|:--|:--|
| A | **Status quo (ADR-0019)** | JSON | CloudEvents binary mode | none |
| B | **A + CI schema gate** | JSON, with committed JSON Schema or Avro-style schema files checked for compatibility in CI | CloudEvents binary mode | none |
| C | **Avro, keep CloudEvents** | Avro, Confluent wire format | CloudEvents binary mode (`ce_*` headers, `content-type: application/avro`) | Confluent Schema Registry |
| D | **Avro, drop CloudEvents** (the experiment) | Avro, Confluent wire format | Plain `event-id`, `event-type`, `event-source`, `event-time` headers | Confluent Schema Registry |

Option C was shown to work in the first phase of the experiment: the CloudEvents SDK carries Avro bytes unchanged, because binary mode treats `data` as opaque. Option D is what the branch now implements.

## 3.1 Option D, as built

* **Producer (Order):** the outbox keeps recording JSON. A new port `EventValueEncoder` (module `polaris-outbox`) turns the recorded JSON into registry-framed Avro at hand-off time; Order supplies the implementation. The relay, retry, ordering and lease logic are unchanged. A registry failure is a failed attempt, retried like a broker failure.
* **Producer (Fulfilment emulator):** encodes shipment events with the same codec and writes the same plain headers.
* **Consumers:** read `event-type` from headers to route, then decode with `AvroEventCodec`.
* **Record layout:** key = aggregate id (unchanged), `traceparent`/`tracestate` unchanged, value = Confluent wire format.
* **Removed:** the `cloudevents-kafka` dependency from the parent, the outbox and both apps, and `CloudEventsKafkaBinding`.
* **Added:** `polaris-events-avro` (schemas, generated classes, mappers, codec), `EventHeaders` constants in `polaris-events`, a `schema-registry` service in compose, `POLARIS_AVRO_SCHEMA_REGISTRY_URL`.

## 4. Comparison

| Criterion | A (status quo) | B (+ CI gate) | C (Avro + CE) | D (Avro, no CE) |
|:--|:--|:--|:--|:--|
| Compatibility enforced before an event is produced | No | Yes, in CI only | Yes, at registration (HTTP 409) | Yes, at registration (HTTP 409) |
| Adheres to a published envelope standard | Yes (CloudEvents 1.0) | Yes | Yes | **No**: bespoke `event-*` headers |
| Registry-framed value is a standard | n/a | n/a | No (Confluent convention) | No (Confluent convention) |
| Runtime dependency added | none | none | registry | registry |
| Payload size | baseline | baseline | about 37% of JSON in the sample | about 37% of JSON in the sample |
| Readable with plain tools | Yes | Yes | headers yes, value no | headers yes, value no |
| Interop with generic CloudEvents tooling and brokers | Yes | Yes | Partly (envelope yes, payload needs registry) | **No** |
| Outbox change | none | none | encoder port | encoder port |
| Evolution rules explicit and tool-checked | by convention | yes | yes | yes |
| Typed money and timestamps | string workaround | same | decimal and timestamp-millis | decimal and timestamp-millis |
| Cost to build | none | low | medium | medium plus replacing every CloudEvents touchpoint |

The size figure is one sample (410 bytes of JSON, 150 bytes of Avro, 155 with registry framing).

## 5. Decision Outcome (proposed)

1. **Do not adopt Avro and a registry now.** The measurable gains are enforcement and size. The costs are a new critical runtime service, loss of plain-text debuggability, a vendor wire format, and a hand-maintained mapper. With two topics and consumers in one repository, the gains do not yet justify the costs.
2. **If a hard contract is wanted now, take option B first.** It gives compatibility checking on committed schemas in CI, needs no runtime service and no change to the outbox, and keeps ADR-0019 intact. The experiment's library-level tests show that Avro's `SchemaCompatibility` runs in plain unit tests without a broker.
3. **If Avro is adopted later, keep the CloudEvents envelope (option C), not option D.** Dropping CloudEvents does not reduce the registry cost and gives up the one standard in the design. The experiment's option D showed that replacing it touches the outbox, two apps, six integration tests, the e2e script and compose, and buys nothing that option C lacks. If D is ever chosen anyway, the `event-*` header names must be documented as a Polaris convention, since no standard backs them.
4. **Revisit when any of these hold:** external partners consume the topics and need a hard contract; several independent teams produce to shared topics; analytics or CDC sinks (Connect, Flink, lake) are planned; payload volume makes JSON size a measured cost; consumers must replay across many schema versions.
5. **If adopted, minimum conditions:** schemas registered from CI with `auto.register.schemas=false`; compatibility mode `BACKWARD_TRANSITIVE` or `FULL_TRANSITIVE` set by provisioning code, not by hand; enums always declare a default; renames use aliases; the registry gets HA, access control, metrics, alerts and a runbook as its own slice before any production use.

## 6. Evidence

Observed in the experiment (tests in `libs/polaris-events-avro`, `libs/polaris-outbox`, `apps/polaris`, `apps/polaris-fulfilment-emulator`):

* The registry refuses a schema with a new required field with HTTP 409, accepts an additive v2, and a v1 reader decodes a v2 record (`RegistryKafkaIntegrationTest`, real Kafka and Confluent Schema Registry in Testcontainers).
* The same breaking change is accepted on a subject configured `NONE`: the guard is configuration.
* Rename of a field that has a default is reported compatible without an alias and silently loses the value (`AvroSchemaEvolutionTest`).
* A v1 reader silently drops fields added in v2: forward compatible, not forward correct.
* With CloudEvents removed, these pass against real Kafka (and PostgreSQL where the app is involved), using a mock registry (`mock://`) for the apps: `KafkaEventTransportIntegrationTest`, `OrderLifecycleKafkaIntegrationTest`, `ShipmentProgressKafkaIntegrationTest`, `PendingEventsFirstStartIntegrationTest`, `FulfilmentEmulatorIntegrationTest`, plus the unit tests `EventKafkaBindingTest` and `OutboxKafkaAutoConfigurationTest`.
* **The registry is on the relay's path, and a test proved it by accident.** `PendingEventsFirstStartIntegrationTest` first failed because it booted the full app with the default registry URL, where nothing listened: every send failed, and the event stayed `PENDING` until the test timed out. That is the intended failure mode (retry, nothing lost), and also the cost: a registry outage now stops all event delivery, not only a broker outage. Compatibility enforcement is covered only by the real-registry test above, because the mock registry does not enforce it.
* Tooling friction: decimals generate as `ByteBuffer` unless `enableDecimalLogicalType=true`; Avro 1.12 refuses to load generated classes unless trusted (handled once in `AvroEventCodec`); Confluent artifacts need `packages.confluent.io`; stale generated sources survive incremental builds.

Not verified: the compose stack with the registry service and the updated `verify-kafka-events.sh` (it now reads through `kafka-avro-console-consumer`); registry HA, access control, performance and retention; a dual-format rollout from JSON to Avro.

## 7. Consequences

### If this ADR is accepted as proposed (stay on ADR-0019, add option B later)
* No runtime change. A follow-up slice adds the CI compatibility gate on committed schemas.
* The branch `experiment/avro-schema-evolution` remains as reference and is not merged.

### If Avro is adopted later (option C)
* **Positive:** enforced compatibility, smaller records, typed money and timestamps, ecosystem fit for Connect and lake sinks.
* **Negative:** the registry becomes critical infrastructure; the relay now depends on it for every send; values need an Avro-aware consumer for debugging; the Confluent wire format is a convention, not a standard; a hand-written mapper (or generated contracts replacing the Jackson records) must be kept in step with the schemas.
* **Operational safeguards:** the five conditions in section 5.5, plus a documented rollout order (consumers first for BACKWARD, producers first for FORWARD) and a dual-read period.

## 8. Open Questions

* Will external parties consume these topics, or is it internal only?
* Is replay from `earliest` a real requirement for fulfilment partners?
* Would the CloudEvents Avro format, rather than the Confluent wire format, matter for interoperability?
* Should the generated Avro classes replace the Jackson contract records in `polaris-events`, removing the mapper at the price of a change for every consumer?
