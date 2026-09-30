# Runbook: TelemetryCollectorDown

- **Severity:** `page`  **Owner:** platform
- **Design:** [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md), section 5 (requirement OBS-PIPE-1)

## What it means

Prometheus cannot scrape the OpenTelemetry Collector self-metrics (`otel-collector:8888`). Applications keep serving (export is asynchronous and bounded, OBS-RES-1) but **all traces, logs and app/span-derived metrics are being lost**, so every other alert is blind.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). For pipeline alerts the metric panel is on the **Telemetry pipeline** dashboard (`polaris-telemetry-pipeline`); the trace and logs steps apply to the failing component's own logs.

1. `docker ps --filter name=otel-collector` and `docker logs --tail 100 otel-collector` (config error, OOM kill, port clash).
2. Prometheus `up{job="otel-collector-self"}` on the pipeline dashboard: since when?
3. `GatewayDown` is inhibited while this fires.

## Mitigation

`docker start otel-collector` (or `docker compose up -d otel-collector`). If it crash-loops, validate the config: `scripts/telemetry/validate-configs.sh`. Telemetry resumes without restarting the applications.

## Escalation

Escalate to the platform owner if it does not stay up after one restart; data sent while it was down is not recoverable. Q2 (production page target) is unanswered; in dev the routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
