# Plan 2: Message Broker Infrastructure

- **Programme:** [Order Notifications & Multi-Partner Fulfilment](README.md) (traceability, TR-X, decisions)
- **Depends on:** [Plan 1](01-outbox-event-publishing.md) · **Unblocks:** [Plan 3](03-fulfilment-emulator.md), [Plan 4](04-notification.md)
- **Status:** Draft for review

## Goal

Kafka becomes part of the platform, and the outbox relay gets a Kafka transport: every recorded event, starting with `order.placed`, arrives on Kafka as a CloudEvent in the originating trace. This plan also sets the conventions every producer and consumer in Plans 3 and 4 follow.

## Technical Requirements

| ID | Requirement |
|:--|:--|
| TR-B1 | **Platform:** single-node Kafka (KRaft) in compose, pinned, healthchecked, reachable by every app on the internal network. Topics are created explicitly by their owner (no auto-create) |
| TR-B2 | **Binding:** events are sent as CloudEvents 1.0 in Kafka **binary mode**, keyed by aggregate id, JSON payload without type headers |
| TR-B3 | **Delivery:** the Kafka transport acknowledges an event only after the broker has durably accepted it, without producer-side duplicates or reordering per key |
| TR-B4 | **Traced:** the recorded trace context becomes the parent of the Kafka produce span and is propagated in the record headers (W3C `traceparent`) |
| TR-B5 | **Topics:** `polaris.order.lifecycle` is owned and provisioned by Order; partition count supports per-order ordering with parallel consumers |
| TR-B6 | **Consumer conventions** (documented, applied by Plans 3–4): consumers join the producer's trace; unknown event types are ignored (forward compatibility); a poison record never blocks a partition forever; each consumer states its starting-offset policy for a newly created group |
| TR-B7 | **Resilience:** Kafka unavailable → Order keeps accepting business requests; events flow once Kafka returns (TR-E2) |

## Slices

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | B1 | Kafka in the platform | `done` | Plan 1 done | `feature/b1-kafka-in-the-platform` | [#19](https://github.com/vungdv/hometask1/pull/19) | |
| 2 | B2 | Kafka transport for the outbox | `in-review` | — | `feature/b2-kafka-transport-for-the-outbox` | [#20](https://github.com/vungdv/hometask1/pull/20) | |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

### B1: Kafka in the platform
**Covers:** TR-B1, TR-X3. **Detail design:** not needed.
- `docker compose up kafka` is healthy; Order starts with Kafka configured and `depends_on` it.
- A Makefile target to inspect topics and tail a topic for local debugging.

### B2: Kafka transport for the outbox
**Covers:** TR-B2–B7, TR-X2, TR-X5. **Detail design:** not needed; the transport port is fixed by Plan 1 E3.
- Against real Postgres and Kafka: a placed order → exactly one `order.placed.v1` record on `polaris.order.lifecycle`, keyed by order number, with all `ce_*` headers and a `traceparent` matching the placing request.
- Kafka stopped while placing → `201` still returned; Kafka restarted → the record arrives. Events pending from before this plan are delivered on first start.
- Several orders placed quickly → per-order order preserved.
- ADR-0019 *Kafka & CloudEvents binding* (topics per owner, binary mode, keys, consumer conventions TR-B6) accepted; ADR-0018 accepted.

## Definition of Done

- [ ] TR-B1–B7 verified by automated tests.
- [ ] In the running stack, placing an order shows `order.placed.v1` on Kafka and a trace in Tempo spanning the HTTP request and the Kafka produce.
- [ ] ADR-0018 and ADR-0019 accepted.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
