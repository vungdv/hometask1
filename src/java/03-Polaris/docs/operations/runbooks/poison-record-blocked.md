# Runbook: PoisonRecordBlocked

- **Severity:** `ticket`  **Owner:** polaris
- **Design:** [O6a async metrics contract](../../development/design/operational-observability/O6a-async-metrics-contract.md) (metric names and labels), [O6c async signal reliability](../../development/design/operational-observability/O6c-async-signal-reliability.md) (blocked-record signal), [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md) (requirement OBS-ASY-1)

## What it means

A consumer group has held one record (`polaris_kafka_consumer_blocked_record_age_seconds`) for more than 5 minutes, for 5 minutes. The gauge counts from the moment the container first handed the record to the listener, retries included. A record keeps failing through the retries (a poison record) or its handler is stuck, so that partition is blocked. Records are skipped and counted in `polaris_kafka_consumer_records_skipped_records_total` once retries are exhausted, so a persistently high value usually means retries are still going or the handler never returns.

**Independent of lag (O6c).** The signal does not use the lag series or the record timestamp, so a blocked last record (lag reads 0 while it is held) is detected, and a group catching up on an old backlog is not flagged (the older `polaris_kafka_consumer_oldest_record_age_seconds` keeps measuring the record's age and is only for the dashboard). The consumer clears the entry on success, skip, partition revoke or loss (rebalance) and container stop, so a rebalance or a stop cannot leave a stale age behind. Before O6c the rule used the record-age series and required lag above 0; both limitations are gone.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). The metric panels are on the **Async: outbox and Kafka** dashboard (`polaris-async`); panels group by `event_type` or consumer `group`, never by order. For the business transaction, search the app logs for the event type around the time the age started to rise.

1. Dashboard `polaris-async`: *Oldest in-flight record age by group* (the `blocked` series is the alerting one) and *Skipped (poison) records by group*; which `group`?
2. Consumer log for that group: the same exception repeating on the same record (`docker logs --tail 300 polaris` or `polaris-fulfilment-emulator`). The error and the record's topic, partition and offset are in the log; the order is in the log line, not in metrics.
3. Check whether the record is really poison (malformed payload, schema mismatch) or the handler is waiting on a dependency (database lock, HTTP call without timeout).

## Mitigation

Poison record: fix the handler or the producer, and let the retries exhaust so it is skipped (the skipped counter increments), or move the group's offset past it after capturing the record for analysis. Stuck handler: fix the dependency, then restart the consumer container. A rebalance or stop clears the state by itself; if the value stays high after one, the consumer is genuinely holding a record, so treat it as stuck and restart the consumer container.

## Escalation

Ticket to the polaris owner. Escalate if business events are blocked for more than a few hours, because downstream state (shipments, partner assignment) is then stale.
