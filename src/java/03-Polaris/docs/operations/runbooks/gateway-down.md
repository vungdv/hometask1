# Runbook: GatewayDown

- **Severity:** `page`  **Owner:** platform
- **Design:** [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md), section 5 (requirement OBS-PIPE-1)

## What it means

The Collector has not been able to scrape the gateway's nginx `stub_status` for 2 minutes (`otelcol_scraper_scraped_metric_points{receiver="nginx"}` is flat): the edge (`https://polaris.local`) is down or the Collector cannot reach it. Every user request is affected. This alert is inhibited while `TelemetryCollectorDown` fires (the Collector is then the cause). (The `nginx_*` gauges themselves are not used for detection: the Collector's Prometheus exporter keeps re-exporting the last values after the gateway dies.)

## First checks (click-path from the alert)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs). For pipeline alerts the metric panel is on the **Edge (gateway)** dashboard (`polaris-edge`); the trace and logs steps apply to the failing component's own logs.

1. `docker ps --filter name=nginx` and `docker logs --tail 100 nginx` (config error, missing upstream host at start, certificate problem).
2. `curl -sk --resolve polaris.local:443:127.0.0.1 https://polaris.local/actuator/health`.
3. If nginx is up but there are no metrics, check the Collector nginx receiver (`docker logs otel-collector | grep -i nginx`) and the internal `:8088/stub_status` listener.

## Mitigation

`docker start nginx`. If it exits at start, run `nginx -t` (see `validate-configs.sh`); nginx cannot start while an upstream host (for example `polaris`) does not exist.

## Escalation

Page the platform owner immediately: this is a full outage of the front door. Q2 (production page target) is unanswered; in dev the routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
