# Runbook: PoisonRecordBlocked

- **Severity:** `ticket`  **Owner:** polaris
- **Design:** [O6a async metrics contract](../../development/design/operational-observability/O6a-async-metrics-contract.md) (metric names and labels), [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md) (requirement OBS-ASY-1)

## What it means

The oldest in-flight record of a consumer group (`polaris_kafka_consumer_oldest_record_age_seconds`) has been older than 5 minutes for 5 minutes while the group still has lag. A record is taken from the broker and not finished: it keeps failing through the retries (a poison record) or its handler is stuck, so that partition is blocked. Records are skipped and counted in `polaris_kafka_consumer_records_skipped_records_total` once retries are exhausted, so a persistently high age usually means retries are still going or the handler never returns.

**Known limitation.** The consumer clears the in-flight entry only on success or skip. If a partition is revoked (rebalance) or the container stops mid-record, the age can keep increasing although nothing is wrong. The alert tolerates this by also requiring the group to report lag above 0 (the lag series disappear or drain after a revoke) and the age series to be fresh (the Collector exporter drops a dead app's series after about 5 minutes). It can still raise a false ticket if a revoked partition's lag series lingers, and it can miss a blocked record whose lag reads 0. In that case compare with *Consumer-group lag by topic* and the consumer log, and restart the consumer if it is a false alert.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). The metric panels are on the **Async: outbox and Kafka** dashboard (`polaris-async`); panels group by `event_type` or consumer `group`, never by order. For the business transaction, search the app logs for the event type around the time the age started to rise.

1. Dashboard `polaris-async`: *Oldest in-flight record age by group* and *Skipped (poison) records by group*; which `group`?
2. Consumer log for that group: the same exception repeating on the same record (`docker logs --tail 300 polaris` or `polaris-fulfilment-emulator`). The error and the record's topic, partition and offset are in the log; the order is in the log line, not in metrics.
3. Check whether the record is really poison (malformed payload, schema mismatch) or the handler is waiting on a dependency (database lock, HTTP call without timeout).

## Mitigation

Poison record: fix the handler or the producer, and let the retries exhaust so it is skipped (the skipped counter increments), or move the group's offset past it after capturing the record for analysis. Stuck handler: fix the dependency, then restart the consumer container. False alert after a rebalance or a stop: restart the consumer to clear the in-flight state.

## Escalation

Ticket to the polaris owner. Escalate if business events are blocked for more than a few hours, because downstream state (shipments, partner assignment) is then stale.
