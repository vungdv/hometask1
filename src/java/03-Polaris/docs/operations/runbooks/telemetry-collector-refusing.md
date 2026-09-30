# Runbook: TelemetryCollectorRefusing

- **Severity:** `ticket`  **Owner:** platform
- **Design:** [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md), section 5 (requirement OBS-PIPE-1)

## What it means

The Collector is refusing spans, metric points or log records at a receiver (usually the `memory_limiter` under memory pressure). Senders may drop data.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). For pipeline alerts the metric panel is on the **Telemetry pipeline** dashboard (`polaris-telemetry-pipeline`); the trace and logs steps apply to the failing component's own logs.

1. Pipeline dashboard: "Refused" and memory panels.
2. `docker stats otel-collector` and `docker logs otel-collector | grep -i "memory\|refus"`.
3. A traffic spike or a cardinality explosion from a new attribute (see the O3 allow-list).

## Mitigation

Reduce the input (find the noisy sender in the accepted-by-receiver panel), or raise the Collector memory limit and the `memory_limiter` limits together in `docker/telemetry/otel-collector-config.yaml`.

## Escalation

Escalate to platform if refusals persist after the noisy source is identified. Q2 (production page target) is unanswered; in dev the routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
