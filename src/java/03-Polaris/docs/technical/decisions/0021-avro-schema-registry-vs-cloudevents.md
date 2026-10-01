# ADR-0021: Avro message schemas + Schema Registry versus CloudEvents JSON for Kafka events

* **Status:** Proposed. Built on branch `experiment/avro-schema-evolution` as **additive** components; **not adopted**. ADR-0019 stays in force and its code is unchanged.
* **Date:** 2026-10-01
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Technical Story:** Evaluate Avro with a Schema Registry for the Kafka events without disturbing the CloudEvents transport that works today.
* **Would supersede (only if adopted):** [ADR-0019](0019-kafka-and-cloudevents-binding.md) §3.1 (record layout) and the "ignore unknown fields" compatibility convention (§4.1 item 2). Builds on [ADR-0018](0018-transactional-outbox-for-integration-events.md).
* **Evidence:** [experiment notes](../experiments/avro-schema-evolution.md), module `libs/polaris-events-avro`.

---

## 1. Context and Problem Statement

ADR-0019 puts every event on Kafka as a CloudEvents 1.0 record in binary mode: `ce_*` headers, a JSON value, key = aggregate id. Compatibility is a convention (consumers ignore unknown types and fields; a breaking change means a new `.vN` type) and nothing checks that producers follow it.

Question: should the payload move to Avro governed by a Schema Registry, and how can that be tried **without changing the code that already works**?

## 2. Decision Drivers

* **Standards first (AGENTS.md Principle 1).**
* **Enforced contracts:** a breaking change should fail before an event is produced (TR-X6).
* **Minimal, bounded change (Principle 2):** the outbox, its relay and the apps are the most heavily tested code; an experiment should not rewrite them.
* **Operability (Principle 3):** a new runtime dependency needs observability, access control and a runbook.
* **Debuggability:** `make kafka-tail`, `verify-kafka-events.sh` and k6 read events as text today.
* **Scale:** two topics, one producer and one consumer each, in one repository.

## 3. Considered Options

| # | Option | Payload | Metadata | Registry | Change to existing code |
|:--|:--|:--|:--|:--|:--|
| A | **Status quo (ADR-0019)** | JSON | CloudEvents `ce_*` headers | none | none |
| B | **A + CI schema gate** | JSON, with committed schemas checked for compatibility in CI | CloudEvents headers | none | none |
| C | **Avro payload inside the CloudEvents envelope** | Avro, Confluent wire format | CloudEvents headers, `content-type: application/avro` | yes | outbox encoder hook |
| D | **Avro message schema, parallel transport** (built) | Avro, Confluent wire format | **inside the Avro message** (id, type, source, time) | yes | **none**: new classes only |

### 3.1 Option D, as built

An **Avro message** is the whole Kafka value: event metadata plus the typed payload. The registry subject `<topic>-value` holds the message schema, so metadata and payload evolve under one compatibility rule. Each destination has one adapter; one transport serves them all.

| Piece | Role |
|:--|:--|
| `OrderLifecycleMessage.avsc`, `ShipmentMessage.avsc` | Message schemas: `id`, `type`, `source`, `time` (timestamp-millis), `data` (the payload record) |
| `AvroMessageMapper` | Adapter port: `destination()` and `toMessage(OutgoingEvent)` |
| `OrderLifecycleMessageMapper` | Maps an `OutgoingEvent` on `polaris.order.lifecycle`: metadata from the event, payload from its JSON via the existing contract record and `OrderLifecycleAvroMapper` |
| `ShipmentMessageMapper` | The same pattern for `polaris.fulfilment.shipments` (the shipment pattern is identical, only the message schema and payload mapper differ) |
| `AvroKafkaEventTransport implements EventTransport` | Picks the mapper by destination, frames the message through `AvroEventCodec`, sends keyed by aggregate id with the W3C trace headers only, returns after the broker acknowledged. Unknown destination, unmappable payload, registry failure or broker timeout throw, so the relay retries |

It sits next to `KafkaEventTransport`, reuses its `producerOverrides` (acks=all, idempotence, bounded sends) and changes none of `polaris-outbox`, `polaris-events`, the apps, compose or the e2e scripts. The diff against the first experiment commit is 12 files, all in `libs/polaris-events-avro`.

## 4. Comparison

| Criterion | A | B | C | D |
|:--|:--|:--|:--|:--|
| Compatibility enforced before an event is produced | No | CI only | Yes (HTTP 409) | Yes (HTTP 409) |
| Published envelope standard | CloudEvents 1.0 | CloudEvents 1.0 | CloudEvents 1.0 | **No**: metadata fields are a Polaris convention |
| Registry-framed value is a standard | n/a | n/a | No (Confluent) | No (Confluent) |
| New runtime dependency | none | none | registry | registry |
| Route on metadata without decoding the value | yes | yes | yes | **no**: decode first |
| Metadata evolves under the registry's rules | no | no | no | **yes** |
| Interop with generic CloudEvents tooling | yes | yes | partly | **no** |
| Plain-text debugging | yes | yes | headers only | trace headers only |
| Payload size | baseline | baseline | about 37% of JSON | about 37% of JSON plus the metadata fields |
| Touches existing code | none | none | outbox | **none** |

The size figure is one sample (410 bytes of JSON against 150 of Avro for the payload alone).

## 5. Decision Outcome (proposed)

1. **Do not adopt Avro and a registry now.** The measurable gains are enforcement and size; the costs are a new critical runtime service, loss of plain-text debuggability, a vendor wire format and per-destination mappers. With two topics and consumers in one repository the gains do not yet justify the costs.
2. **If a hard contract is wanted soon, take option B first.** It needs no runtime service and no code change. The experiment shows Avro's `SchemaCompatibility` runs in plain unit tests without a broker.
3. **Keep the experiment as the reference for option D.** It proves the adapter and transport approach end to end without touching the CloudEvents path, so adoption can be incremental: switch one topic by selecting `AvroKafkaEventTransport` for it.
4. **Between C and D, prefer C unless one-rule evolution of metadata matters.** D removes CloudEvents' standard envelope and header routing for the benefit of registry-governed metadata; the experiment found no other advantage.
5. **Revisit when:** external partners consume the topics and need a hard contract; several teams produce to shared topics; analytics or CDC sinks are planned; payload volume makes JSON size a measured cost; consumers must replay across many schema versions.
6. **If adopted, minimum conditions:** schemas registered from CI with `auto.register.schemas=false`; compatibility `BACKWARD_TRANSITIVE` or `FULL_TRANSITIVE` set by provisioning code; enums declare a default; renames use aliases; the registry gets HA, access control, metrics, alerts and a runbook as its own slice.

## 6. Evidence

Measured in `libs/polaris-events-avro` (real Kafka `apache/kafka:3.9.1` and real `cp-schema-registry:8.0.0` in Testcontainers):

* `AvroKafkaEventTransportIntegrationTest`: an order `OutgoingEvent` arrives as one Avro message (id, type, source, time and payload decoded back equal to the contract record), keyed by the aggregate, with only `traceparent` and `tracestate` as headers and no `ce_*`; the registry holds `OrderLifecycleMessage` under `polaris.order.lifecycle-value`; a shipment `OutgoingEvent` is delivered by the same transport with the shipment adapter; an unknown destination or an unmappable payload fails the send; an unreachable registry fails the send.
* `MessageMappersTest`: metadata comes from the event, time is truncated to milliseconds, a payload that does not match the contract fails mapping.
* `RegistryKafkaIntegrationTest`: a breaking schema is refused with HTTP 409; an additive v2 is accepted and a v1 reader decodes v2 records; the same breaking change is accepted on a subject set to `NONE`, so the guard is configuration.
* `AvroSchemaEvolutionTest`: the compatibility matrix; a rename of a defaulted field passes the check without an alias and silently loses the value; a v1 reader silently drops fields added in v2.
* The existing `CloudEventsKafkaBindingTest`, `KafkaEventTransportIntegrationTest` and `OutboxKafkaAutoConfigurationTest` still pass unchanged.
* Tooling friction: decimals generate as `ByteBuffer` unless `enableDecimalLogicalType=true`; Avro 1.12 refuses to load generated classes unless trusted (handled once in `AvroEventCodec`); Confluent artifacts need `packages.confluent.io`; message schemas that embed payload records need a second codegen execution with `imports`; stale generated sources survive incremental builds.

**Not done:**
* No Spring auto-configuration selects `AvroKafkaEventTransport`: a service would construct it, or an opt-in auto-configuration must be added.
* No app, compose file or e2e script uses it. The registry is not in compose.
* There is no consumer-side adapter beyond `AvroEventCodec.decode`.
* The shipment adapter maps an `OutgoingEvent`, but the fulfilment emulator publishes straight to Kafka without the outbox, so using the adapter there means moving it onto `EventTransport` first.
* Registry HA, access control, performance, retention and a dual-format rollout are not covered.

## 7. Consequences

### If this ADR is accepted as proposed
* No runtime change. A follow-up slice may add the CI compatibility gate (option B). The branch stays as a reference and is not merged.

### If option D is adopted later
* **Positive:** enforced compatibility for metadata and payload together; smaller records; typed money and timestamps; the CloudEvents path keeps working during migration.
* **Negative:** the registry becomes critical and sits on the relay's path (an outage stops all delivery of Avro-routed events, not only a broker outage); consumers must decode before routing; the metadata fields and the Confluent wire format are conventions, not standards; one mapper per destination to maintain.
* **Operational safeguards:** the conditions in section 5.6, a documented rollout order and a dual-read period.

## 8. Open Questions

* Will external parties consume these topics, or is it internal only?
* Is replay from `earliest` a real requirement for fulfilment partners?
* Would the CloudEvents Avro format, rather than a bespoke message schema, matter for interoperability?
* Should the fulfilment emulator move onto the outbox transport port so the shipment adapter can be used?
