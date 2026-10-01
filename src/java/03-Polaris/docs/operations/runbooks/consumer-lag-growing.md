# Runbook: ConsumerLagGrowing

- **Severity:** `ticket`  **Owner:** polaris
- **Design:** [O6a async metrics contract](../../development/design/operational-observability/O6a-async-metrics-contract.md) (metric names and labels), [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md) (requirement OBS-ASY-1)

## What it means

A Kafka consumer group has had more than 100 records of lag for 10 minutes and the lag is still increasing (`async:consumer_lag:records`, summed per group and topic from the Kafka client metric `kafka_consumer_fetch_manager_records_lag`, dotted topic series only). The consumer is alive but slower than the producers: downstream state (for example shipment status in Order, partner assignment in the emulator) is getting later. A consumer that is **gone** has no lag series and is covered by `AsyncMetricsMissing`.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). The metric panels are on the **Async: outbox and Kafka** dashboard (`polaris-async`); panels group by `event_type` or consumer `group`, never by order. For the business transaction, search the app logs for the event type around the time the age started to rise.

1. Dashboard `polaris-async`, row Kafka consumer groups: which `group` and `topic`, and is *Oldest in-flight record age* also high (a slow or stuck handler rather than volume)?
2. Production rate: *Publish success and failure by event type* shows the inflow; a burst (for example a k6 batch) can legitimately outrun a consumer for a while.
3. Consumer logs: `docker logs --tail 200 polaris-fulfilment-emulator` or `polaris` for retries, timeouts or slow downstream calls; **Dependencies** dashboard for database and HTTP latency.
4. Rebalances: repeated assignment messages in the logs mean the group is not stable (long handler, missed poll interval).

## Mitigation

Remove the cause of the slowness (slow dependency, stuck handler). If volume is the cause and the group has more partitions than consumers, scale the consumer. The alert resolves when the lag stops growing or drops to 100 or below.

## Escalation

Ticket to the polaris owner. Escalate to the platform owner if lag grows on every group at once (broker problem).
