# Detail Design: O5 — SLOs, Alerts and Runbooks

- **Plan:** [Operational Observability](../../plan/operational-observability.md), slice **O5**
- **Covers:** OBS-SLO-1, OBS-PIPE-1 (alerting), OBS-DIA-1 (click-path in the runbooks)
- **Decisions:** D2 (Prometheus rules + Alertmanager), D5 (Mailpit + webhook), D6 (tier-0 = `polaris`, `polaris-assistant`, `nginx` gateway)
- **Status:** For review. **Every target, threshold and window below is a proposal**; the SLI definitions and burn-rate windows are the part to review first. Q2 (who receives pages in production) is **unanswered**, so only dev routing is designed.

## 1. Inputs that shaped the design (checked on the live stack, 2026-09-30)

| Finding | Consequence |
|:--|:--|
| Span metrics (`traces_spanmetrics_*`, Tempo) label the service as `service` (`Polaris`, `Polaris-Assistant`, `nginx-gateway`). App metrics (`http_server_requests_milliseconds_*`, via the Collector) label it `exported_job` with the same capitalised names | Recording rules **normalise** both to one `service` label with lower-case values `polaris`, `polaris-assistant`, `nginx-gateway` and add an `owner` label. Alerts only ever see the normalised series |
| The app histogram `http_server_requests_milliseconds_bucket` has **only the `+Inf` bucket** in Prometheus | It cannot serve a latency SLI. Latency comes from span metrics (`traces_spanmetrics_latency_bucket`, seconds, buckets 0.002 … 16.384 doubling). The app histogram is still used for availability (`_count` by `status`) |
| Span metrics carry `polaris_sse` only on gateway spans (`polaris.sse` is set by nginx, O2a). Application spans have no route dimension | The only place to exclude SSE streams (`/mcp/`, `/api/v1/assistant`) from latency is the gateway. Latency of `polaris`/`polaris-assistant` is therefore **measured at the gateway**, selected by the route prefix in `span_name` (`polaris:*`, `assistant:*`). Measuring on the app's own spans would count every open SSE stream as a slow request |
| Collector self-metrics have no `_total` suffix (`otelcol_receiver_refused_spans`); failure counters (`send_failed_*`, `enqueue_failed_*`) exist only after the first failure | Pipeline rules use those names, and tolerate absent series with `or vector(0)` |
| Mailpit is **not** in the stack yet (the plan calls it "already in the stack"; it is only referenced by the notification plan) | O5 adds a pinned Mailpit container, alongside Alertmanager and a webhook receiver |

## 2. SLIs

An event is one HTTP request that reached the service. Health-check probes are counted (they are traffic and they fail when the service fails); this is a known simplification.

| SLI (`sli` label) | Service (`service`) | Good event | Total events | Source |
|:--|:--|:--|:--|:--|
| `availability` | `nginx-gateway` (edge) | status is not 5xx | all non-SSE server spans of the gateway (`polaris_sse="false"`) | `traces_spanmetrics_calls_total{service="nginx-gateway",span_kind="SPAN_KIND_SERVER",polaris_sse="false"}`, bad = `http_response_status_code=~"5.."` |
| `availability` | `polaris`, `polaris-assistant` | status is not 5xx | all requests | `http_server_requests_milliseconds_count{exported_job=…}`, bad = `status=~"5.."` |
| `latency` | `nginx-gateway` | non-SSE request completes in ≤ T | all non-SSE server spans of the gateway | `traces_spanmetrics_latency_bucket{le="T"}` / `_count` |
| `latency` | `polaris`, `polaris-assistant` | as above, only routes proxied to that service | gateway non-SSE spans with `span_name=~"polaris:.*"` / `"assistant:.*"` | as above |

4xx are not errors (they are the client's). SSE routes are excluded from **both** edge SLIs, since a stream's status is decided at connect time and its duration is by design unbounded. 5xx from an upstream (`502/503/504`) are visible at the edge, which is why the edge SLI is the truest user-facing one.

### Targets (proposals)

Window: **30 days**, error budget = `1 − target`.

| Service | Availability target | Budget | Latency target | Threshold T (must be a span-metrics bucket) |
|:--|:--|:--|:--|:--|
| `nginx-gateway` | 99.5 % | 0.5 % | 95 % of requests ≤ T | 1.024 s |
| `polaris` | 99.5 % | 0.5 % | 95 % ≤ T | 1.024 s |
| `polaris-assistant` | 99.0 % | 1.0 % | 95 % ≤ T | 8.192 s (LLM-backed) |

**Constraint found while unit-testing:** a burn factor of 14.4× needs an error ratio of `14.4 × budget`, which is only reachable (≤ 100 %) when the budget is under 1/14.4 ≈ 6.9 %. A first draft with a 90 % assistant latency target (10 % budget) could never fast-page, so no target may be looser than about 93 %. The targets are recorded as data (`sli:error_budget:ratio{service,sli,owner}`, file `slo-targets.rules.yml`), so changing an SLO is a one-line rule change plus a unit-test update.

## 3. Recording rules (pipeline of series)

```
raw metrics ──► sli:requests:rate5m{service,sli,owner}       (total events/s)
                sli:bad_requests:rate5m{service,sli,owner}   (bad events/s)     [normalisation happens here]
          ──► sli:error_ratio:window{service,sli,owner,window}  windows 5m 30m 1h 2h 6h 1d 3d
          ──► alerts: sli:error_ratio:window / sli:error_budget:ratio  = burn rate
```

- The **base** series are 5-minute rates at 30 s evaluation, with static `service`/`sli`/`owner` labels per rule. This is where `exported_job` and `service` are unified.
- The window ratio is `sum_over_time(bad[W]) / sum_over_time(requests[W])` (the `5m` window uses the base ratio directly). This averages 30 s samples of a 5 m rate rather than re-reading raw counters for every window: it is an approximation (edges are smoothed by up to 5 m), chosen to keep 14 base rules generic and cheap. A 3-day window is ~8 640 samples per series. If exactness is needed later, replace the base rate with per-window `increase()` in the rules only; alerts and tests do not change.
- **History guard** (found on the live stack): a window's ratio is produced only when at least 80 % of its 30 s samples exist (`count_over_time(...) >= 0.8 * window / 30 s`). Without it, a freshly started or restarted Prometheus computes the "3 day" ratio from a few minutes of data and pages on a handful of requests (observed: real burn alerts fired within minutes on an idle dev stack). Consequence: after a cold start the 1 h alerts arm after about 48 min, the 6 h after about 4.8 h, the 1 d after about 19 h and the 3 d after about 2.4 d; the 5 m and 30 m windows alone never page (each alert needs its long window).
- 0/0 (no traffic) yields no sample, so no alert; a **minimum-traffic guard** (`sli:requests:rate5m > 0.05`, about 3 requests/minute) also stops a single failed request from paging on an idle system. Consequence, stated honestly: a total outage of a service with under 3 requests/minute does not page through burn rate; the gateway-down alert and the service dashboards cover that case.

## 4. Burn-rate alerts

Burn rate = observed error ratio ÷ error budget. A burn of 1 spends exactly the 30-day budget in 30 days. Windows and factors follow the Google SRE Workbook multi-window, multi-burn-rate table; each alert requires **both** its long and short window to exceed the factor (the short window makes the alert reset quickly after recovery).

| Alert | Factor | Long window | Short window | Budget spent at trigger | Severity | Route |
|:--|:--|:--|:--|:--|:--|:--|
| `SloBurnFast` | 14.4× | 1 h | 5 m | 2 % in 1 h | `page` | email + webhook |
| `SloBurnMedium` | 6× | 6 h | 30 m | 5 % in 6 h | `page` | email + webhook |
| `SloBurnSlow` | 3× | 1 d | 2 h | 10 % in 1 d | `ticket` | email |
| `SloBurnSlowest` | 1× | 3 d | 6 h | 10 % in 3 d | `ticket` | email |

Each rule is generic over `service` and `sli` (six SLIs, so 24 alert conditions from four rule definitions). Labels: `severity`, `owner`, `service`, `sli`. Example thresholds: at a 99.5 % target the fast page needs an error ratio above 7.2 % on both 1 h and 5 m; at 99.0 % above 14.4 %.

## 5. Pipeline alerts (OBS-PIPE-1)

| Alert | Condition (summary) | For | Severity | Owner |
|:--|:--|:--|:--|:--|
| `TelemetryCollectorDown` | `up{job="otel-collector-self"} == 0` or no series | 2 m | `page` | platform |
| `TelemetryCollectorRefusing` | receiver refused spans + metric points + log records rate > 0 | 5 m | `ticket` | platform |
| `TelemetryCollectorDropping` | exporter `send_failed_*` (after retries) + `enqueue_failed_*` (queue full) rate > 0 | 5 m | `ticket` | platform |
| `TelemetryExporterQueueNearFull` | `queue_size / queue_capacity > 0.8` | 5 m | `ticket` | platform |
| `TelemetryBackendDown` | `up{job=~"tempo\|loki\|grafana"} == 0` | 2 m | `ticket` | platform |
| `GatewayDown` | the Collector's nginx scraper stops scraping points (`rate(otelcol_scraper_scraped_metric_points{receiver="nginx"}[2m]) == 0`, or the series is absent) | 1 m on top of the 2 m rate window | `page` | platform |
| `AlertmanagerDown` | `up{job="alertmanager"} == 0` | 2 m | `ticket` | platform |

Notes:
- `GatewayDown` is derived from the Collector's nginx scrape. Absence of `nginx_connections_current` is **not** usable: verified live that the Collector's Prometheus exporter keeps re-exporting the last nginx values for 20+ minutes after the gateway stops (a first draft using `absent_over_time` on it never fired). It is **inhibited** while `TelemetryCollectorDown` fires (both would otherwise appear when the Collector is the failed part).
- Prometheus cannot alert on its own death and Alertmanager cannot report itself; a dead-man's switch to an external receiver is the standard answer and needs a production target (Q2). Not built.
- Ingest lag (OBS-PIPE-1) has no Tempo/Loki metric wired into the dashboards yet; the queue-near-full and dropping alerts are the proxy. Recorded as a gap.

## 6. Alertmanager

- Image `prom/alertmanager:v0.28.1`, healthcheck on `/-/ready`, config mounted read-only from `docker/telemetry/alertmanager/alertmanager.yml`, state in a named volume. Port `9093` is published to **127.0.0.1 only** (dev UI); O7 decides real exposure.
- Routing tree: match `severity` (`page`/`ticket`) then `owner` (`platform`, `polaris`, `assistant`). `page` receivers have an **email** (Mailpit SMTP) and a **webhook** integration; `ticket` receivers email only. Anything unmatched falls to a catch-all email receiver so nothing is silently dropped.
- Inhibition: `severity=page` inhibits `ticket` for the same `service`+`sli`; `TelemetryCollectorDown` inhibits `GatewayDown`.
- **No secrets are committed.** Dev needs none (Mailpit accepts unauthenticated SMTP on the private network; the webhook target is an internal URL). For a real SMTP relay or webhook, Alertmanager reads secrets from files (`smtp_auth_password_file`, `url_file`), mounted from the environment's secret store (12-factor); the dev file shows no credential fields.
- **Dev receivers (D5):** Mailpit (`axllent/mailpit`, UI on 127.0.0.1:8025, SMTP `mailpit:1025` internal only) and a webhook echo (`mendhak/http-https-echo`, internal only, logs every request to stdout). **Production routing is not designed:** Q2 is unanswered.

## 7. Annotations and links

Every alert has annotations `summary`, `description`, `service`, `severity`, `dashboard_url`, `runbook_url`, and labels `severity`, `owner` (and `service` for burn alerts).

- `dashboard_url` = `https://grafana.polaris.local/d/<uid>/…` using O4 uids: `polaris-edge` (gateway), `polaris-service-red` (apps), `polaris-telemetry-pipeline` (pipeline), `polaris-async` (outbox and Kafka, O6b).
- `runbook_url` = repository blob URL of `docs/operations/runbooks/<alert-name-kebab>.md`.

## 8. Runbooks and the click-path (OBS-DIA-1)

`docs/operations/runbooks/` holds one file per alert plus `README.md` with the shared **click-path** (from an alert to metric panel, exemplar trace and correlated logs in 4 steps):

1. Open `dashboard_url` from the alert email (the metric panel for the affected service and route).
2. On the failing/slow series, click an **exemplar** dot to open the trace in Tempo.
3. In the trace, click **Logs for this span** (Tempo → Loki, by `trace_id`).
4. In the logs, read `orderNumber` and the error; use it to find the business transaction (traces and logs only, never metrics).

Each runbook: what it means, first checks, mitigation, escalation.

## 9. Verification

| What | How | Where it runs |
|:--|:--|:--|
| Rule syntax | `promtool check rules` on the pinned Prometheus image | `validate-configs.sh` (CI) |
| Alert behaviour | `promtool test rules`: fast burn fires (edge, app-metric service, assistant latency), slow burn (ticket) fires while fast/medium do not, healthy traffic fires nothing, low-traffic guard, history guard, SSE exclusion, label normalisation, every pipeline alert and a healthy pipeline | `validate-configs.sh` (CI) |
| Alertmanager config | `amtool check-config`; routing tests with `amtool config routes test` (severity × owner → receiver, unknown → catch-all) | `validate-configs.sh` |
| Alert metadata | every alert has the six annotations; `runbook_url` files exist; `dashboard_url` uids exist in the provisioned dashboards | `scripts/telemetry/alerts-validate.sh` (from `validate-configs.sh`) |
| Live | rules load, targets up; `scripts/telemetry/alerts-check.sh` induces failures against the running stack using a **test-only, short-window copy** of the burn rules (generated from the real file, never committed) and asserts the alert in Alertmanager, the email in Mailpit and the webhook request | manual / O8 |

What live verification **cannot** prove: the real 1 h/6 h/1 d/3 d windows (they need that much history; they are proven only by the unit tests, which drive them with synthetic series), and any production route.

## 10. Out of scope

Kafka/outbox alerts (O6), retention and port exposure (O7), real page target (Q2), dead-man's switch, tier-1 dependency availability alerts (Postgres, Kafka, Keycloak, Redis: they have no metrics scraped yet).
