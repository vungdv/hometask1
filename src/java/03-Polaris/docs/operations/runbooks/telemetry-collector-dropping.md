# Runbook: TelemetryCollectorDropping

- **Severity:** `ticket`  **Owner:** platform
- **Design:** [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md), section 5 (requirement OBS-PIPE-1)

## What it means

Exports from the Collector to a backend fail after retries, or the bounded sending queue was full and data was dropped (`send_failed` / `enqueue_failed` rates above zero for 5 minutes). Some telemetry is permanently lost.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). For pipeline alerts the metric panel is on the **Telemetry pipeline** dashboard (`polaris-telemetry-pipeline`); the trace and logs steps apply to the failing component's own logs.

1. Pipeline dashboard: "Export failures" by exporter tells you which backend (`otlp_grpc/tempo`, `otlp_http/logs`, ...).
2. `docker ps` and health of `tempo` / `loki`; `TelemetryBackendDown` may also be firing.
3. `docker logs otel-collector --tail 100` for the exporter error (auth, 429 rate limit, connection refused).

## Mitigation

Fix the backend (restart it, free disk, raise limits). The Collector retries and resumes by itself; no restart is needed.

## Escalation

Escalate to platform when the backend cannot be restored quickly; note the data gap. Q2 (production page target) is unanswered; in dev the routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
