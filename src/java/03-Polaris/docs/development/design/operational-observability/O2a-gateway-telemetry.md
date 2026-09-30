# Detail Design: O2a — Gateway (nginx) Telemetry

- **Plan:** [Operational Observability](../../plan/operational-observability.md), slice **O2a**
- **Covers:** OBS-EDGE-1, OBS-TRC-1 (edge hop), OBS-PIPE-1 (gateway health); OBS-RES-1 (edge)
- **Decision:** plan D7 (official `-alpine-otel` image, syslog logs, span-derived metrics); D1 (keep the Collector); D3 (Tempo span metrics)
- **Status:** Implemented in this slice

## 1. Pinned image (verified 2026-09-30)

`nginx:1.31.6-alpine-otel` (digest `sha256:f237a3efb7233a400a89c2bbb16c68dc4036de731c143a2a87160b3c9238f0b3` at pull time). It contains `/etc/nginx/modules/ngx_otel_module.so`, `curl` (used by the compose healthcheck) and nginx 1.31.6. The floating tag `nginx:alpine-otel` resolves to the same version. The plain `nginx:alpine` image has no `ngx_otel_module`.

## 2. Confirmed directives (`nginx -t` and a live request against the pinned image)

| Need | Directive | Notes |
|:--|:--|:--|
| Load module | `load_module modules/ngx_otel_module.so;` | main context |
| Exporter | `otel_exporter { endpoint otel-collector:4317; interval 5s; batch_size 512; batch_count 4; }` | OTLP/gRPC, async and batched; drops when the Collector is unreachable |
| Resource | `otel_service_name nginx-gateway;` | becomes `service.name` |
| Sampling | `split_clients "$request_id" $otel_sample { 100% on; * off; }` + `otel_trace $otel_sample;` | decided once here; 100% in dev |
| Propagation | `otel_trace_context propagate;` | continues an incoming `traceparent`, else starts a trace; injects `traceparent` upstream |
| Span name | `otel_span_name "$route";` | `$route` is set per `location` (fixed list), so the name is low-cardinality |
| Span attributes | `otel_span_attr <key> "<value>";` | see 3 |
| Trace/span ids in logs | `$otel_trace_id`, `$otel_span_id` | |
| Logs | `access_log syslog:server=otel-collector:5514,facility=local7,tag=nginx,severity=info edge_json;` and `access_log /dev/stdout edge_json;` | UDP; never blocks a worker |
| Health | `stub_status;` on `listen 8088` | internal only |

Findings that shaped the design:

1. **Module default attributes leak the query string, and `otel_span_attr` cannot fix it.** The module always sets `http.target` to the raw request target including `?code=...&state=...` (and old semconv names). `otel_span_attr http.target ...` *appends a duplicate* rather than replacing it, so the leaking value still reaches Tempo (found in review; a dict-style read hid the duplicate). No module option removes it in this image. Fix: the Collector `transform/nginx_spans` processor (traces pipeline, second after `memory_limiter`, scoped to `service.name == nginx-gateway`) deletes every `http.target`/`url.query` attribute; nginx adds `url.path "$uri"`, `http.request.method`, `http.response.status_code`. `edge-check.sh` scans the raw trace (all spans of both services, duplicates included) for a planted secret.
2. **The `syslog:` target is resolved once at nginx startup** (`host not found in syslog server` aborts start, and the same for `proxy_pass` hosts). To make the gateway independent of the Collector (OBS-RES-1, including a restart while the Collector is down), `polaris-net` has a pinned subnet `172.29.250.0/24` (dynamic addresses limited to the upper half via `ip_range`), the Collector has the fixed address `172.29.250.10`, and nginx maps `otel-collector` to it with `extra_hosts`. nginx no longer `depends_on` the Collector. Verified: nginx restarts with the Collector stopped and serves HTTP 200. Known limits: upstream hosts (`polaris`, `keycloak`, ...) are still resolved at start, so nginx cannot start while, for example, `polaris` does not exist (pre-existing behaviour); the pinned subnet may clash on a host that already uses `172.29.250.0/24`.
3. `nginx -t` in CI needs the hostnames resolvable; the validation script passes `--add-host <name>:127.0.0.1` for each.

## 3. Span, log and route model

Routes are the named `location`s, never raw URLs:

| `$route` | Location | `$sse` |
|:--|:--|:--|
| `polaris:/` | `/` on `polaris.local` | false |
| `polaris:/mcp/` | `/mcp/` | **true** |
| `assistant:/api/v1/assistant` | `/api/v1/assistant` | **true** |
| `assistant:/v3/api-docs`, `swagger-ui`, `swagger-ui.html` | doc endpoints | false |
| `keycloak:/`, `grafana:/` | other vhosts | false |
| `http-redirect` | port 80 redirect | false |

Span attributes: `http.route`, `polaris.sse`, `http.request.method`, `http.response.status_code`, `url.path` (no query); `http.target` is removed in the Collector. `polaris.sse` is a Tempo span-metrics dimension so O5 can exclude streaming routes from the latency SLI.

Access log (JSON, `escape=json`): `time, trace_id, span_id, host, route, sse, uri, method, status, upstream_status, request_time, upstream_response_time, bytes_sent`. It deliberately uses no `$args`, `$request`, `$request_uri`, `$http_*` or `$cookie_*` variable, so the query string, `Authorization`, cookies and OAuth `code`/`state` cannot be logged.

## 4. Collector wiring

```
nginx --OTLP/gRPC 4317--> otel-collector [traces pipeline] --> Tempo --span metrics--> Prometheus
nginx --syslog/UDP 5514--> otel-collector [logs/nginx pipeline] --> Loki (service.name=nginx-gateway)
otel-collector [nginx receiver] --HTTP--> nginx:8088/stub_status --> [metrics/nginx] --> Prometheus
```

- `syslog` receiver (rfc3164, UDP `:5514`, not published to the host) feeds a dedicated `logs/nginx` pipeline: `memory_limiter` first, `resource/nginx` (`service.name=nginx-gateway`), `transform/nginx_logs`, `batch`, then the existing bounded `otlp_http/logs` exporter. No new exporter, so O1 policy (memory_limiter first, bounded queues, retry) holds unchanged.
- `transform/nginx_logs` parses the JSON, promotes `trace_id`/`span_id` to the log record (so Loki structured metadata and the Tempo-to-logs query from O2 work unchanged), sets severity ERROR for status ≥ 500, and keeps the JSON line as the body.
- `transform/nginx_spans` sits in the traces pipeline (after `memory_limiter`) and removes the leaking `http.target`.
- `nginx` receiver scrapes `stub_status` every 10 s into the `metrics/nginx` pipeline (`nginx_connections_current`, requests, etc.).
- `stub_status` listens on `8088` inside `polaris-net` only (not in compose `ports`), with `allow` limited to loopback and private ranges. The compose healthcheck uses it.

## 5. Failure behaviour (OBS-RES-1)

| Event | Result |
|:--|:--|
| Collector stops while nginx runs, or nginx restarts while it is down | Spans dropped by the exporter, syslog datagrams lost; requests unaffected; nginx starts normally |
| Polaris stops | Docker drops the stopped container without a TCP reset, so the 3 s `proxy_connect_timeout` yields `504` (a refused connection gives `502`); span has that status; both visible in Loki and span metrics |
| Loki/Tempo down | Absorbed by the Collector's bounded queues (O1) |

## 6. Verification

- Static: `scripts/telemetry/validate-configs.sh` (pinned image, `nginx -t` on the pinned image, directive and no-leak policy checks, Collector policy including the new pipeline).
- Live: `scripts/telemetry/edge-check.sh` (trace root and child, log/trace id equality in stdout and Loki, no secrets, `stub_status` internal-only, span and connection metrics); `RUN_FAILURE=1` adds the stop-polaris and stop-collector scenarios.

## 7. Out of scope

Redaction processors (O3), the Edge dashboard (O4), SLOs and alerts (O5), gateway `error_log` shipping to Loki.
