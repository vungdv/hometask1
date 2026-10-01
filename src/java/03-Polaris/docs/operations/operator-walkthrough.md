# Operator walk-through: alert to business transaction (OBS-DIA-1)

Plan slice O8. Uses only provisioned objects: alert annotations from `docker/telemetry/prometheus/rules/`, provisioned dashboards, and the provisioned datasource correlations (Prometheus exemplar to Tempo, Tempo to Loki, Loki derived field to Tempo). **Nothing is created or edited in Grafana by hand.** Per-alert detail is in the [runbooks](runbooks/README.md); this page is the worked, verified example.

Grafana: `https://grafana.polaris.local` (Keycloak login, Viewer or above). Alertmanager and Mailpit are internal; `make up-dev-ports` publishes them on loopback (`http://localhost:9093`, `http://localhost:8025`).

## Click-path: alert to business transaction

At most 4 steps from the alert email.

| Step | Where | Action | What you learn |
|:--|:--|:--|:--|
| 1. Metric panel | Alert email, field `dashboard_url` | Open the link. Edge alerts open `polaris-edge`; service alerts open `polaris-service-red` with `var-service` preselected; async alerts open `polaris-async`; pipeline alerts open `polaris-telemetry-pipeline`. | Affected service and route, when it started, how bad (rate, 5xx, p95) |
| 2. Exemplar trace | Panel *p95 latency by route (non-SSE)* (Edge) or *Duration p50 / p95 / p99* (Service RED) | Click an exemplar dot on the offending series. Grafana opens that trace in Tempo (provisioned exemplar destination). Fallback: Explore, Tempo, `{ resource.service.name = "nginx-gateway" && status = error }`, or the dashboard `polaris-traces-logs-explorer` tables *Recent error traces* / *Slowest traces*. | Which request, which span failed or was slow |
| 3. Correlated logs | Trace view | Click **Logs for this span**. Tempo runs `{service_name="<svc>"} \| trace_id="<trace id>"` on Loki (provisioned, `customQuery`). | The error text and context of that exact request |
| 4. Business transaction | Log lines | Read `orderNumber=ORD-...` in the log line (present in traces and logs, never in metrics). To go the other way, search Loki `{service_name="Polaris"} \|= "orderNumber=ORD-..."`; the `trace_id` derived field has a **View trace** link. | The affected order, and its whole trace (HTTP, outbox, Kafka, consumers) |

If a step needs anything not provisioned, there is none: all four hops are datasource correlations or dashboard panels in `docker/telemetry/grafana/provisioning/`.

## Verified on the live stack

`tests/e2e/observability/walkthrough-check.sh` runs each hop through Grafana's own datasource proxy (`/api/datasources/proxy/uid/<uid>/...`, the same requests the UI makes) with the Grafana admin credentials from `.env`. Screenshots were **not** captured (no browser session was available to the agent that wrote this); the evidence is the API output below. A person should still click through once in a browser; the click targets above are the provisioned panels and links named in the datasource and dashboard files.

Run on 2026-10-01 against the running dev stack (all PASS; Prometheus returns the `dashboard_url` annotation as an unexpanded Alertmanager template, the email shows the expanded link):

```
== step 1: alert -> metric panel
PASS SloBurnFast dashboard_url template -> https://grafana.polaris.local/d/polaris-edge/edge-gateway (gateway) or .../d/polaris-service-red/service-red?var-service=... 
PASS dashboard is provisioned and has an exemplar-enabled panel
PASS panel-style PromQL answers through Grafana
== step 2: panel -> exemplar trace
PASS exemplar traceID from Prometheus via Grafana: 7da1e360d90a90359b187539d6068693
PASS Tempo opens that trace through Grafana
== step 3 / 4: business trace
PASS order ORD-10000147 -> trace_id 2dd7ee41adfb6166c3ab3e44d5f75815 (LogQL: {service_name="Polaris"} |= "orderNumber=ORD-10000147")
PASS {service_name="Polaris"} | trace_id="2dd7ee41..." returns the order's logs (orderNumber=ORD-10000147)
PASS the same trace opens in Tempo through Grafana
PASS no Prometheus series has an order-number label
```

Not verified by a browser: that the exemplar dot and the **Logs for this span** button render and click as described. The API calls behind them (exemplar query, Tempo `customQuery` to Loki, Loki derived field) are what is verified, and `scripts/telemetry/correlation-check.sh` asserts the provisioned correlation settings.

## Queries used

| Step | Datasource | Query |
|:--|:--|:--|
| 1 | Prometheus | `histogram_quantile(0.95, sum by (le, service)(rate(traces_spanmetrics_latency_bucket{service="nginx-gateway"}[5m])))` (panel query, exemplars enabled) |
| 2 | Prometheus | `query_exemplars` on `traces_spanmetrics_latency_bucket{service="Polaris"}`, then Tempo `GET /api/traces/<traceID>` |
| 3 | Loki | `{service_name="Polaris"} \| trace_id="<trace id>"` |
| 4 | Loki | `{service_name="Polaris"} \|= "orderNumber=ORD-..."` |
