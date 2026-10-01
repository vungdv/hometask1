# Runbooks

One runbook per Prometheus alert (plan O5). Every alert carries a `runbook_url` that points to one of the files below; `scripts/telemetry/alerts-validate.sh` fails if a linked file is missing or an alert has no runbook.

| Alert | Severity | Runbook |
|:--|:--|:--|
| `SloBurnFast` | page | [slo-burn-fast](slo-burn-fast.md) |
| `SloBurnMedium` | page | [slo-burn-medium](slo-burn-medium.md) |
| `SloBurnSlow` | ticket | [slo-burn-slow](slo-burn-slow.md) |
| `SloBurnSlowest` | ticket | [slo-burn-slowest](slo-burn-slowest.md) |
| `TelemetryCollectorDown` | page | [telemetry-collector-down](telemetry-collector-down.md) |
| `TelemetryCollectorRefusing` | ticket | [telemetry-collector-refusing](telemetry-collector-refusing.md) |
| `TelemetryCollectorDropping` | ticket | [telemetry-collector-dropping](telemetry-collector-dropping.md) |
| `TelemetryExporterQueueNearFull` | ticket | [telemetry-exporter-queue-near-full](telemetry-exporter-queue-near-full.md) |
| `TelemetryBackendDown` | ticket | [telemetry-backend-down](telemetry-backend-down.md) |
| `GatewayDown` | page | [gateway-down](gateway-down.md) |
| `AlertmanagerDown` | ticket | [alertmanager-down](alertmanager-down.md) |
| `OutboxStuck` | page | [outbox-stuck](outbox-stuck.md) |
| `ConsumerLagGrowing` | ticket | [consumer-lag-growing](consumer-lag-growing.md) |
| `PoisonRecordBlocked` | ticket | [poison-record-blocked](poison-record-blocked.md) |
| `AsyncMetricsMissing` | ticket | [async-metrics-missing](async-metrics-missing.md) |

## Click-path: alert to metric, exemplar trace and logs

From an alert email (or Alertmanager, `http://localhost:9093` in dev with `make up-dev-ports`) to the evidence, in **4 steps** (OBS-DIA-1):

1. **Metric panel.** Open the `dashboard_url` annotation: Edge (gateway) (`polaris-edge`), Service RED (`polaris-service-red`, service preselected), Async: outbox and Kafka (`polaris-async`) or Telemetry pipeline (`polaris-telemetry-pipeline`). The failing series is visible on the request-rate, 5xx or p95 panels.
2. **Exemplar trace.** On the request/latency panel, click an exemplar dot on the offending series; Grafana opens the trace in Tempo (Prometheus exemplar to Tempo, provisioned in O2). Or use Explore, Tempo, `{ resource.service.name = "nginx-gateway" && status = error }`.
3. **Correlated logs.** In the trace view choose **Logs for this span** (Tempo to Loki by `trace_id`, provisioned in O2). Loki opens filtered to that trace.
4. **Business transaction.** In the log lines read the error and the `orderNumber` (present in traces and logs, never in metrics) to identify the order or operation affected.

Dev notification targets (Q2, the production target, is unanswered): Mailpit `http://localhost:8025`, Alertmanager `http://localhost:9093` (both published on loopback only by `make up-dev-ports`, see [exposure](../exposure.md)), webhook receiver `docker logs alert-webhook`.
