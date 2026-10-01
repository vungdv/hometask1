# Detail Design: O6a — Async Metrics Contract (outbox and Kafka)

- **Plan:** [Operational Observability](../../plan/operational-observability.md), slice **O6**, part (a) (application/library side)
- **Covers:** OBS-ASY-1 (measurable). Alerting, panels and runbooks are part (b), a separate change that consumes only this contract.
- **Status:** Implemented in this change. Names, units and labels below are a contract (Principle 2): part (b) may rely on them; changing them is a breaking change.

## 1. What existed (checked on the live stack)

| Signal | Before this change |
|:--|:--|
| Outbox backlog, oldest-pending age | `polaris_outbox_backlog_events`, `polaris_outbox_oldest_pending_age_seconds`: totals only, no event type |
| Outbox publish outcome | `polaris_outbox_handoff_milliseconds_*{outcome}`: `destination` was set in code but is **dropped by the O3 allow-list**; no event type |
| Kafka lag | Kafka client metric `kafka_consumer_fetch_manager_records_lag{client_id,topic,partition}` for the emulator only. **No consumer-group label** (the group is only embedded in `client_id`), topic appears twice (`polaris.x` and `polaris_x`, a Kafka-client/Micrometer artefact), and **Order's `order.shipments` consumer had no client metrics at all** (its factory is built without a Micrometer listener) |
| Oldest-record age | Not available. The Kafka client exposes lag in records, never in time |

## 2. Outbox metrics (library `polaris-outbox`, exported by `polaris`)

Prometheus names are those produced by the Collector's exporter from the OTLP names (unit suffix appended).

| OTLP name | Prometheus name | Type, unit | Labels | Meaning |
|:--|:--|:--|:--|:--|
| `polaris.outbox.backlog` | `polaris_outbox_backlog_events` | gauge, events | none | Pending events in total. Always present (0 when empty): the series to alert on |
| `polaris.outbox.oldest.pending.age` | `polaris_outbox_oldest_pending_age_seconds` | gauge, seconds | none | Age of the oldest pending event (now − `occurred_at`); 0 when none |
| `polaris.outbox.pending` | `polaris_outbox_pending_events` | gauge, events | `event_type` | Pending events of one CloudEvents type. Series appear when a type is first seen pending and then report 0 when drained |
| `polaris.outbox.pending.oldest.age` | `polaris_outbox_pending_oldest_age_seconds` | gauge, seconds | `event_type` | Oldest pending age of one type; 0 when none |
| `polaris.outbox.handoff` | `polaris_outbox_handoff_milliseconds_{count,sum,bucket}` | timer | `outcome` (`success`/`failure`), `event_type`, `destination` | One transport hand-off attempt. **Publish success/failure = `_count` split by `outcome`**: failure ratio is `rate(..._count{outcome="failure"}) / rate(..._count)` |
| `polaris.outbox.delivery.lag` | `polaris_outbox_delivery_lag_milliseconds_*` | timer | `event_type`, `destination` | Time from recording to accepted hand-off |
| `polaris.outbox.purged` | `polaris_outbox_purged_events_total` | counter | none | Retention purge (unchanged) |

Why totals and per-type are separate names: a metric must not mix labelled and unlabelled series, and the totals must stay present when nothing is pending (no per-type series then exists). Dashboards join and group on `event_type`; alerts use the totals.

Semantics: gauges are read when sampled by the registry. **Since O6c the database is read on a dedicated thread, never on the registry's publish thread** (a bounded wait of 2 s in total, a 5 s statement timeout, snapshots cached 5 s and not reported once older than 30 s): see [O6c](O6c-async-signal-reliability.md). Per-type values come from one grouped query cached for 5 s, so a scrape costs one query. If the database is unreachable the gauges are not exported (NaN); that outage is itself visible through the app's own health and DB metrics. A pending event with an old age and increasing `outcome="failure"` counts means "outbox stuck".

## 3. Kafka consumer-group metrics (class `ConsumerGroupMetrics`, wired in `polaris` and `polaris-fulfilment-emulator`)

| Prometheus name | Type, unit | Labels | Meaning |
|:--|:--|:--|:--|
| `kafka_consumer_fetch_manager_records_lag` | gauge, records (Kafka client metric) | `group`, `topic`, `partition`, plus client labels | **Consumer-group lag.** Same client metric as before, now carrying an explicit `group` label (a Micrometer listener tag per group). Aggregate with `sum by (group, topic)`; use the dotted `topic` series only (the underscore twin is an exporter artefact) |
| `polaris_kafka_consumer_oldest_record_age_seconds` | gauge, seconds | `group` | **Oldest-record age.** Age (now − record timestamp) of the oldest record the group's consumer has taken from the broker and not finished. A record failing and being retried stays in flight, so a blocked partition makes this grow; idle or caught-up groups report 0 |
| `polaris_kafka_consumer_blocked_record_age_seconds` | gauge, seconds | `group` | **Blocked-record age (added in O6c, additive).** How long the oldest unfinished record has been held by the consumer: from the first time the container hands that offset to the listener (kept across retries), 0 when idle. Independent of record timestamp and of lag; cleared on success, skip, partition revoke/loss and container stop. The poison-record alert uses this one |
| `polaris_kafka_consumer_records_skipped_records_total` | counter | `group`, `topic` | Records the error handler gave up on after retries (poison record skipped). The series first appears at the first skip, so alerts must tolerate its absence |

Honest limits of this approach:
- Lag and age are reported **by the consumer itself**. If the consumer app is down or cannot reach Kafka, its series go stale or vanish; part (b) must alert on absence (`up`/missing series), and could add a broker-side exporter if group lag with a dead consumer is needed. A broker-side exporter was not chosen here because O6a is the application side and adds no new component.
- "Oldest-record age" is the oldest *in-flight* record, not the oldest unfetched one. Records still queued at the broker behind a slow consumer are visible only as lag (records), which is why both signals exist. In-flight record age is the backlog-style age; the blocked-record age (O6c) is the stuck-handler/poison-record signal, lag is the backlog signal.
- The Kafka client's per-partition lag meters are bound by Micrometer on a 60 s cycle after partition assignment, so lag series appear up to a minute after a start or rebalance.
- The emulator's per-partner groups (`fulfilment.<partner>`) now each get their own consumer factory so each carries its own `group` label; the old `spring_id` label value changed accordingly.

## 4. Labels and the O3 allow-list

Allowed new label keys are all small, fixed sets: `event_type` (CloudEvents types), `group` (consumer groups), `destination` (outbox logical destinations), `outcome` (already allowed). **Never** `orderNumber`, `user_id`, `order_*`, event ids or keys (guarded by a test that rejects any label other than the contract set).

The Collector allow-list (`docker/telemetry/otel-collector-config.yaml`, `transform/metric_allowlist`) dropped `event_type`, `group` and `destination`; this change extends it minimally with the key families `event`, `group` and `destination`. Side effect to note: any metric attribute key starting with `event.`/`event_` is now allowed; metric authors must keep such keys bounded.

## 5. Tests

- `OutboxMetricsH2Test`: real schema and relay; totals, per-type count and age, age growth, drained type reports 0, hand-off outcome per event type for failure and success, and label set is only the contract (no order identifiers).
- `ConsumerGroupMetricsTest`: in-flight age maths, oldest partition wins, failing record keeps blocking, skip clears and counts, groups independent.
- `ConsumerGroupMetricsKafkaIntegrationTest`: real broker; a blocked record raises the age, client metrics carry the `group` tag, a poison record is skipped and counted, age returns to 0.
- Existing Kafka integration tests of both apps still pass with the new wiring.
