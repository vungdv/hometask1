# Runbook: TelemetryExporterQueueNearFull

- **Severity:** `ticket`  **Owner:** platform
- **Design:** [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md), section 5 (requirement OBS-PIPE-1)

## What it means

An exporter's sending queue is over 80% full for 5 minutes: the backend is slow or down and the Collector will start dropping when the queue is full (by design, to protect the applications).

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). For pipeline alerts the metric panel is on the **Telemetry pipeline** dashboard (`polaris-telemetry-pipeline`); the trace and logs steps apply to the failing component's own logs.

1. Pipeline dashboard: "Exporter queue utilisation" names the exporter.
2. Health and latency of the matching backend (`tempo`, `loki`).
3. Is ingest volume unusually high (accepted-by-receiver panel)?

## Mitigation

Restore or speed up the backend; if the load is legitimate, raise the queue size within the O1 bound (`sending_queue.queue_size`).

## Escalation

Escalate to platform if it reaches 100% (then `TelemetryCollectorDropping` fires). Q2 (production page target) is unanswered; in dev the routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
