# Runbook: AlertmanagerDown

- **Severity:** `ticket`  **Owner:** platform
- **Design:** [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md), section 5 (requirement OBS-PIPE-1)

## What it means

Prometheus cannot scrape Alertmanager (`alertmanager:9093`): alerts still evaluate in Prometheus but cannot be delivered (no email, no webhook). Because Alertmanager cannot report its own failure, this alert only shows in Prometheus and Grafana.

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). For pipeline alerts the metric panel is on the **Telemetry pipeline** dashboard (`polaris-telemetry-pipeline`); the trace and logs steps apply to the failing component's own logs.

1. Prometheus `/alerts` and Grafana still show firing alerts; check them manually.
2. `docker ps --filter name=alertmanager` and `docker logs --tail 100 alertmanager` (config error).
3. `amtool check-config docker/telemetry/alertmanager/alertmanager.yml` (or `scripts/telemetry/validate-configs.sh`).

## Mitigation

`docker start alertmanager`; fix any config error and `docker compose up -d alertmanager`.

## Escalation

Escalate to platform. A dead-man's-switch to an external receiver would close this gap but needs a production target (Q2, unanswered). Q2 (production page target) is unanswered; in dev the routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
