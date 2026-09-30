# Runbook: SloBurnFast (page)

- **Severity:** `page`  **Owner:** taken from the alert's `owner` label (`platform` = gateway, `polaris`, `assistant`)
- **Design:** [O5 SLOs, alerts and runbooks](../../development/design/operational-observability/O5-slos-alerts-runbooks.md)

## What it means

The SLO for `service` / `sli` (`availability` = non-5xx ratio, `latency` = share of non-SSE requests under the threshold) is burning its 30-day error budget at 14.4x or faster on **both** the 1h and 5m windows (2% of the monthly budget gone in an hour). Targets are proposals; see the design document.

- `nginx-gateway` = edge (user-facing view, SSE routes excluded).
- `polaris` and `polaris-assistant` availability = 5xx ratio from application metrics; their latency is measured at the gateway on non-SSE routes.

## First checks (click-path from the alert, at most 4 steps)

See the [shared click-path](README.md#click-path-alert-to-metric-exemplar-trace-and-logs).

1. Open `dashboard_url` from the alert email: Edge (gateway) for `service=nginx-gateway`, Service RED for the applications (the link carries the service).
2. On the offending series, click an **exemplar** dot to open the trace in Tempo.
3. In the trace, choose **Logs for this span** (Tempo to Loki, by `trace_id`).
4. In the logs read the error and the `orderNumber`; it identifies the business transaction (traces and logs only, never metrics).

Then narrow it down:
- Availability: which `http_route` and `http_response_status_code` carry the 5xx (Edge dashboard, "5xx by route"). `502/504` mean the upstream is unreachable or slow; `500/503` come from the application.
- Latency: which route is slow (Edge p95 by route); is the upstream or the dependency (Postgres, Kafka, Keycloak, LLM provider) slow (Service RED, Dependencies dashboard).
- Was there a deploy or restart? `docker ps` (`Up` time) and the application logs.

## Mitigation

Act now. Restore the failing upstream first (restart or roll back the last change), then confirm the error ratio falls on the 5m window within minutes and the alert resolves. A stopped or unreachable upstream shows as edge `502/504`: `docker ps`, `docker start <service>`.

## Escalation

If the burn continues for more than 30 minutes, or the cause is a dependency you do not own, escalate to the owner in the alert's `owner` label and record the incident. Q2 (production page target) is unanswered; in dev the routing is Mailpit (`http://localhost:8025`) and the webhook receiver.
