# Runbook: AsyncMetricsMissing

- **Severity:** `ticket`  **Owner:** polaris
- **Design:** [O6a async metrics contract](../../development/design/operational-observability/O6a-async-metrics-contract.md) (metric names and labels), [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md) (requirement OBS-ASY-1)

## What it means

The outbox gauges (`polaris_outbox_backlog_events`), the `order.shipments` consumer, or the fulfilment emulator's consumers (`polaris_kafka_consumer_oldest_record_age_seconds`) have not reported for 15 minutes (the Collector exporter keeps last values for 5 minutes, then the alert waits 10 more). These metrics are reported by the apps themselves, so while they are missing `OutboxStuck`, `ConsumerLagGrowing` and `PoisonRecordBlocked` cannot fire: the async path is unobserved, not necessarily broken.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). The metric panels are on the **Async: outbox and Kafka** dashboard (`polaris-async`); panels group by `event_type` or consumer `group`, never by order. For the business transaction, search the app logs for the event type around the time the age started to rise.

1. `docker ps` for `polaris` and `polaris-fulfilment-emulator`; `docker logs --tail 100 <name>` for startup or Kafka connection failures.
2. Is the Collector forwarding? See `TelemetryCollectorDown` and the **Telemetry pipeline** dashboard; query `polaris_outbox_backlog_events` in Prometheus.
3. The outbox gauges are not exported when the database is unreachable (NaN): check `/actuator/health` of `polaris`.
4. Consumer series appear up to a minute after a consumer starts or rebalances; the alert allows for this.
5. Is the whole stack missing the same window? Query `up{job="prometheus"}` over the gap: if Prometheus' own scrape has the same hole, the host or Docker VM was suspended (a sleeping laptop freezes everything) and there is nothing to fix. A hung database no longer stops the app's other metrics (O6c: gauges read the database on their own thread), so a gap in only the outbox series points to the database or the app's DB pool; a gap in every `polaris` series with the emulator still reporting points to the app itself (thread dump with `docker exec polaris kill -3 1`, then `docker logs polaris`).

## Mitigation

Restart or repair the missing app or the Collector. The alert resolves when the series are reported again.

## Escalation

Ticket to the polaris owner; if the Collector is the cause, hand over to the platform owner (`TelemetryCollectorDown` is a page).
