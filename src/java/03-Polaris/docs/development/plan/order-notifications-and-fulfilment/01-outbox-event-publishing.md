# Plan 1: Outbox & Generic Event Publishing

- **Programme:** [Order Notifications & Multi-Partner Fulfilment](README.md) (traceability, TR-X, decisions)
- **Depends on:** — · **Unblocks:** [Plan 2](02-message-broker.md)
- **Status:** Draft for review

## Goal

A reusable way for any bounded context that owns a database to publish events **reliably**: an event is stored if and only if the business change commits, and a relay delivers it later through a pluggable **transport port**, with retries and per-key ordering. Order is the first adopter: placing an order records `order.placed`.

No message broker yet. Plan 2 adds the Kafka transport. Until then, recorded events stay pending, which is expected.

## Technical Requirements

| ID | Requirement |
|:--|:--|
| TR-E1 | **Atomic:** an event is recorded if and only if the business change that raised it commits. Publishing outside a transaction fails fast |
| TR-E2 | **No loss:** transport outages and app restarts never fail the business request and never lose a recorded event; pending events are delivered once the transport is available |
| TR-E3 | **Ordered per key:** events for the same aggregate are delivered in commit order, including across failures, retries and multiple app instances |
| TR-E4 | **At-least-once** with a stable event id (`ce_id`), assigned when recorded, for consumer dedupe |
| TR-E5 | **Generic:** business code raises **domain events** and depends only on a publish abstraction, never on a broker. Domain events are translated into **integration events** (published contracts). A new event type or a new transport needs no change to the publishing mechanism or its storage |
| TR-E6 | **Operable:** failed deliveries retry with backoff and are never silently dropped; backlog size and oldest-pending age are observable; delivered events are purged after a retention period |
| TR-E7 | **Traced:** the trace context of the request that raised an event is recorded with it and handed to the transport |
| TR-E8 | **Timely:** commit → transport hand-off under 1 s in normal operation |
| TR-E9 | **Contracts:** all PRD-007 integration events (the 5 `order.*.v1` and 3 `shipment.*.v1`, with `assignedPartner` and `partnerId`, Δ4) are defined up front in a dependency-free library, so later plans only consume them |
| TR-O1 | A newly placed order raises `order.placed`. A rolled-back placement or an idempotent replay raises nothing |

## Slices

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | E1 | Event contracts library | `in-progress` | — | | | |
| 2 | E2 | Recording events atomically | `todo` | Flyway V15 still free | | | |
| 3 | E3 | Relay to a transport port | `todo` | — | | | |
| 4 | E4 | Order records `order.placed` | `todo` | Gaps plan S4 merged | | | |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

### E1: Event contracts library
**Covers:** TR-E9, TR-X3, TR-X6, Δ5. **Detail design:** not needed.
- A new library with no Spring or persistence dependencies holds the event records, event type names and logical destinations (EM-002 §4 plus Δ4).
- JSON round-trip: money stays a string, unknown fields are ignored, a null `assignedPartner` is accepted.
- All existing images still build with the new module in the reactor.

### E2: Recording events atomically
**Covers:** TR-E1, TR-E4, TR-E5, TR-E7, TR-X4 (V15). **Detail design: required.**
- Proven with a test-only event and no Order code, against real Postgres: commit → exactly one recorded event with a unique id and trace context; rollback → none; publishing outside a transaction → fails fast.
- The publish abstraction is broker-agnostic and reusable by any context with a datasource; apps without a datasource aren't forced to have one.
- ADR-0018 *Transactional outbox for integration events* drafted and proposed.

*Design questions:* storage schema; how domain events are raised and translated so that no state change can skip its event; library packaging and auto-configuration.

### E3: Relay to a transport port
**Covers:** TR-E2, TR-E3, TR-E6, TR-E8, TR-X2. **Detail design: required** (may extend E2's note).
- Against real Postgres and a test transport (the only stub, at the external boundary): recorded events are handed off in commit order per key and marked delivered.
- Transport failing → events stay pending and retry with backoff; transport recovers → all delivered, in order per key. A failing event holds back later events for the same key only.
- Two app instances → no reordering and no double hand-off in normal operation. A crash between hand-off and marking delivered → re-sent with the same id.
- No transport configured → the relay stays idle and events accumulate as pending, without errors.
- Retention purge never touches pending events. Backlog, oldest-pending age and delivery lag metrics exposed.

*Design questions:* relay trigger (polling and/or post-commit signal); multi-instance ordering strategy; backoff limits; transport port shape (what a transport receives: key, destination, payload, headers, trace context).

### E4: Order records `order.placed`
**Covers:** TR-O1, TR-E5 (first adopter). **Detail design:** covered by E2.
- A placed order → exactly one recorded `order.placed` event, committed with the order, keyed by order number, carrying customer, items and total, in the placing request's trace.
- Rejected placement (stock, price) → none. Idempotent replay, including the gaps plan's concurrent-duplicate path → none.
- Order code depends only on the publish abstraction.

## Definition of Done

- [ ] TR-E1–E9 and TR-O1 verified by automated tests.
- [ ] Placing an order with no transport configured still returns `201` and leaves one pending `order.placed` event.
- [ ] ADR-0018 is proposed.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
