# Runbook: TelemetryBackendDown

- **Severity:** `ticket`  **Owner:** platform
- **Design:** [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md), section 5 (requirement OBS-PIPE-1)

## What it means

Prometheus cannot scrape a telemetry backend (`job` label: `tempo`, `loki` or `grafana`). Traces, logs or dashboards are unavailable; the Collector queues and then drops data.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). For pipeline alerts the metric panel is on the **Telemetry pipeline** dashboard (`polaris-telemetry-pipeline`); the trace and logs steps apply to the failing component's own logs.

1. `docker ps --filter name=<job>` and `docker logs --tail 100 <job>`.
2. Disk space and volume health (`docker system df`), config errors after a change.
3. Pipeline dashboard: "Targets up".

## Mitigation

`docker start <job>` (or `docker compose up -d <job>`). Telemetry buffered in the Collector queues drains on return; the Collector needs no restart.

## Escalation

Escalate to platform if the container will not stay up (check `scripts/telemetry/validate-configs.sh`). Q2 (production page target) is unanswered; in dev the routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
