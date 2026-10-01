# Runbook: OutboxStuck

- **Severity:** `page`  **Owner:** polaris
- **Design:** [O6a async metrics contract](../../development/design/operational-observability/O6a-async-metrics-contract.md) (metric names and labels), [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md) (requirement OBS-ASY-1)

## What it means

The oldest pending outbox event (`polaris_outbox_oldest_pending_age_seconds`) has been older than 5 minutes for 5 minutes while the backlog (`polaris_outbox_backlog_events`) is above 0. Orders are accepted and their events are stored, but are not reaching Kafka, so fulfilment and every downstream consumer stop progressing. The relay normally drains within about a second, so this is never normal load. Nothing is lost: events stay in the outbox table and are delivered, in order, once the cause is fixed.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). The metric panels are on the **Async: outbox and Kafka** dashboard (`polaris-async`); panels group by `event_type` or consumer `group`, never by order. For the business transaction, search the app logs for the event type around the time the age started to rise.

1. Dashboard `polaris-async`, row Outbox: which `event_type` is pending (panel *Pending events by event type*), and is *Publish success and failure* showing failures (Kafka unreachable) or no attempts at all (relay not running)?
2. Kafka health: `docker ps --filter name=kafka` and `docker logs --tail 100 kafka-1`. In dev all three brokers must be up for the cluster to accept writes.
3. App side: `docker logs --tail 200 polaris | grep -i -E "outbox|kafka"` (relay errors, timeouts) and the `/actuator/health` of `polaris` (database reachable).
4. If the age is high with **no** failures and no attempts, the relay itself is not polling: check the application logs for relay exceptions and the database connection pool panel on **Dependencies**.

## Mitigation

Restore Kafka (`docker start kafka-1 kafka-2 kafka-3`) or the app database connection. The relay resumes by itself: pending count and age fall to 0 and the alert resolves after the next evaluation. Do not delete outbox rows by hand. If events were published but marked pending after a crash, consumers must be idempotent (at-least-once delivery) and duplicates are expected.

## Escalation

Page the polaris owner (this alert is a `page`). If Kafka cannot be restored in 30 minutes, escalate to the platform owner. Q2 (production page target) is unanswered; in dev routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
