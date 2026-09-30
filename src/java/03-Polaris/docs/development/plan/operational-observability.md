# Plan: Operational Observability on the Grafana Stack

- **Requirement:** [`docs/operations/high-level-requirement.md`](../../operations/high-level-requirement.md), rewritten by slice O0 into testable requirements
- **Design baseline:** [ADR-0016](../../technical/decisions/0016-genai-observability-and-mcp-audit-standards.md), [ADR-0006](../../technical/decisions/0006-grafana-domain-routing-and-tls-architecture.md)
- **Code verified against:** `main` @ `5ae191b` (2026-09-30)
- **Status:** Draft for review

## Goal

Turn the eight high-level requirement areas into numbered, measurable requirements. Then make the existing Grafana stack meet them, so an operator can go from an alert to a metric, a trace, a log and a root cause without leaving Grafana.

## Current State (verified)

| Area | Today | Gap |
|:--|:--|:--|
| Ingest | Apps → OTLP/HTTP → `otel-collector` → Tempo (traces), Loki `/otlp` (logs), Prometheus exporter `:8889` (metrics) | No self-telemetry from the collector; `memory_limiter` only on traces; `attributes` processor inserts keys from themselves (a no-op); `debug` exporter prints every log at `detailed` verbosity, which leaks data |
| Images | Loki pinned to `3.1.0`; Prometheus, Tempo, Grafana and the collector are `:latest` | Not reproducible; conflicts with TR-X3 (pinned images) |
| Edge | `nginx:alpine` is the single ingress for `polaris.local`, `id.polaris.local` and `grafana.polaris.local`. Default access log, no tracing, no metrics, no healthcheck | Edge `502/504`, TLS and routing failures are invisible (apps never see them); traces start at the app, not the user's request; the access log is unstructured and, on `id.polaris.local`, would contain OAuth `code` query strings |
| Health | No healthchecks on any telemetry container | Compose cannot order or detect a dead backend |
| Correlation | Three bare datasources (`url` only) | No trace ↔ log ↔ metric links, no exemplars, no service graph; Tempo has no `metrics_generator`; Prometheus has no remote-write receiver |
| Dashboards | None provisioned (only `datasources/`) | Nothing to look at on day one |
| Alerting / SLOs | None | No SLIs, rules, routes or runbooks; `docs/operations/` is a placeholder |
| Retention | Not set on Loki, Tempo or Prometheus | Unbounded disk growth; no stated policy |
| PII / cardinality | Collector has no redaction | Redaction and cardinality rules are unenforced |
| Access | Grafana login via Keycloak, role mapped from claims | Datasource and folder permissions are not declared; telemetry ports `3100`, `3200`, `9090`, `4317`, `4318` are published to the host |
| Async | Outbox and Kafka exist (Plans 1–3) | No outbox-pending, event-age or consumer-lag signal |

## Decisions

| ID | Question | Recommendation | Slice |
|:--|:--|:--|:--|
| D1 | Add Grafana Alloy or keep the OTel Collector? | **Keep the Collector.** It is the OpenTelemetry standard (Principle 1) and already wired; Alloy adds a component with no new capability here | O1 |
| D2 | Alerting: Grafana-managed or Prometheus rules + Alertmanager? | **Prometheus rule files + Alertmanager.** Standard PromQL, unit-testable with `promtool`, provisioned as files. Grafana displays them. One new container | O5 |
| D3 | Span-derived metrics: Tempo `metrics_generator` or collector `spanmetrics`? | **Tempo `metrics_generator`** (span metrics and service graph) remote-writing to Prometheus. One place, and it feeds the Grafana service map | O2 |
| D4 | Tenant dimensions and multi-tenant backends? | **Out of scope.** Polaris has no tenants today (confirmed 2026-09-30). The requirement drops its tenant wording; revisit if tenants are introduced | O0 |
| D5 | Alert notification target in dev? | **Mailpit** (already in the stack) over SMTP, plus a webhook receiver to prove routing | O5 |
| D6 | Which components are tier-0? | **`polaris`, `polaris-assistant` and the `nginx` gateway** (confirmed 2026-09-30; gateway added the same day, because it is the first hop for every request and the only place edge failures show). SLOs and burn-rate alerts apply to them. Everything else is tier-1/2 (see Tiers) | O0, O5 |
| D7 | How does `nginx` emit telemetry? | **Official `nginx:<version>-alpine-otel` image** (ngx_otel_module) exports **traces** over OTLP to the Collector; **logs** as JSON access logs sent over syslog (UDP, non-blocking) to a Collector `syslog` receiver; **metrics** come from the gateway's server spans via Tempo span metrics (D3) plus `stub_status` for connections. No new agent (D1) | O2a |

## Technical Requirements

Requirement IDs live in the rewritten requirement document; this table is what O0 must produce, so the slices can be traced to it.

| ID | Requirement |
|:--|:--|
| OBS-STD-1 | Names its standards explicitly: OpenTelemetry + semantic conventions, OTLP, W3C Trace Context, CloudEvents for messaging, Prometheus exposition/rule format |
| OBS-VIS-1 | Every production component declares a tier (0/1/2), per *Tiers* below. Each tier states its instrumentation floor, SLO and alerting level |
| OBS-TRC-1 | 100% of inbound HTTP and Kafka entrypoints continue an incoming `traceparent`. One business operation is one trace across HTTP → outbox → Kafka → consumers |
| OBS-MET-1 | Each tier-0 service exports request rate, error rate and duration histograms, plus JVM and dependency metrics. **No `user_id` or `order_*` metric labels.** A per-metric series cap is stated and checked |
| OBS-LOG-1 | Logs are JSON with `trace_id`, `span_id`, `service.name` and business keys; nothing is written to protocol `stdout`; secrets and PII are redacted **before** storage |
| OBS-EDGE-1 | The gateway starts (or continues) the trace for every inbound request and propagates `traceparent` upstream; its JSON access log carries `trace_id`, `span_id`, route, status and upstream status, and never a query string, `Authorization` header or cookie; edge availability (non-5xx ratio) and latency are SLIs; an upstream `502/503/504` is visible at the edge |
| OBS-ASY-1 | Outbox pending count, oldest-pending age, publish failures, and per-group Kafka consumer lag and oldest-record age are measurable and alertable |
| OBS-SLO-1 | SLIs and SLOs per tier-0 service; multi-window burn-rate alerts; each alert carries service, severity, dashboard link and `runbook_url` |
| OBS-DIA-1 | From an alert, a documented click-path reaches the metric panel, an exemplar trace, and the correlated logs, in at most 4 steps |
| OBS-RES-1 | Stopping any telemetry backend or the collector does not fail or slow business requests (export is async, bounded and drops when full) |
| OBS-SEC-1 | Telemetry access is role-based (Keycloak); retention is stated and configured per signal; backends are not exposed except through the gateway; redaction is enforced in the collector |
| OBS-PIPE-1 | The telemetry pipeline is monitored: collector accepted/refused/dropped, exporter queue and failures, backend up, ingest lag. It alerts on its own failure |

## Tiers

Proposed in O0; the tier-0 choice is decided (D6), the rest is a default to confirm.

| Tier | Components | Floor |
|:--|:--|:--|
| 0 | `polaris`, `polaris-assistant`, `nginx` (gateway) | RED metrics, traces, structured logs, SLOs with burn-rate alerts, runbooks |
| 1 | Platform dependencies they need: Postgres, Kafka, Keycloak, Redis | Health and dependency metrics, availability alert |
| 2 | `polaris-fulfilment-emulator`, dev tooling (Mailpit, Swagger UI, `gcx-cli`) | Traces and logs only, no paging |

## Slices

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | O0 | Requirements rewrite and ADR | `done` | — | `docs/o0-observability-requirements` | [#28](https://github.com/vungdv/hometask1/pull/28) | Docs only; blocks the rest |
| 2 | O1 | Pipeline hardening and self-monitoring | `done` | O0 | `feat/o1-pipeline-hardening` | [#31](https://github.com/vungdv/hometask1/pull/31) | |
| 3 | O2 | Signal correlation and span metrics | `done` | O1 | `feat/o2-signal-correlation` | [#32](https://github.com/vungdv/hometask1/pull/32) | |
| 4 | O2a | Gateway (nginx) telemetry | `done` | O1, O2 | `feat/o2a-gateway-telemetry` | [#33](https://github.com/vungdv/hometask1/pull/33) | Lands before O3 so redaction covers edge logs |
| 5 | O3 | Redaction and cardinality guardrails | `done` | O1, O2a | `feat/o3-redaction-guardrails` | [#34](https://github.com/vungdv/hometask1/pull/34) | |
| 6 | O4 | Dashboards as code | `in-review` | O2, O2a | `feat/o4-dashboards-as-code` | [#35](https://github.com/vungdv/hometask1/pull/35) | |
| 7 | O5 | SLOs, alerts and runbooks | `todo` | O2, O4 | | | |
| 8 | O6 | Async visibility (outbox and Kafka) | `todo` | O2 | | | Touches `libs/polaris-outbox` and Kafka; see note |
| 9 | O7 | Access, retention and exposure | `todo` | O1 | | | |
| 10 | O8 | End-to-end verification | `todo` | O1–O7, O2a | | | |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

### O0: Requirements rewrite and ADR
**Covers:** OBS-STD-1, OBS-VIS-1, and the ID scheme for every other requirement. **Detail design:** not needed.
- `docs/operations/high-level-requirement.md` is restructured with stable IDs, MUST/SHOULD wording (RFC 2119) and a measurable acceptance criterion per requirement. It gains a standards table, a component-tier table, non-goals, and a data-governance section (PII handling, retention). The tenant wording in §3, §7 and §8 is removed.
- Open questions below are resolved or carried forward as explicit non-goals.
- ADR-0020 *Observability platform on the Grafana stack* records D1–D5. `docs/operations/README.md` links the new structure.
- Self-check: every requirement row in this plan appears in the document with the same ID.

### O1: Pipeline hardening and self-monitoring
**Covers:** OBS-RES-1, OBS-PIPE-1, TR-X3. **Detail design:** not needed.
- All telemetry images are pinned to explicit versions, each with a healthcheck. `depends_on` uses `service_healthy`.
- Collector: `memory_limiter` first in every pipeline; the no-op `attributes` processor and the `debug` exporter are removed (or kept at `basic` behind a compose profile); every exporter has a bounded `sending_queue` and `retry_on_failure`; self-metrics on `:8888` are scraped by Prometheus.
- Prometheus scrapes the collector, Tempo, Loki, Grafana and itself.
- Stopping `tempo`, `loki` or `otel-collector` while a k6 or curl load runs: business requests keep their latency and status (measured); telemetry resumes without a restart when the backend returns.

### O2: Signal correlation and span metrics
**Covers:** OBS-DIA-1, OBS-TRC-1 (verification side). **Detail design:** not needed.
- Datasources are provisioned with full `jsonData`: Tempo → Loki (trace to logs by `trace_id`), Tempo → Prometheus (trace to metrics), service map; Loki derived field → Tempo; Prometheus exemplar → Tempo.
- Tempo `metrics_generator` (span-metrics, service-graphs) remote-writes to Prometheus; Prometheus enables the remote-write receiver and exemplar storage.
- Starting from a failing request in Grafana, one click reaches the trace and one more reaches its logs. A trace from a placed order spans HTTP → outbox → Kafka produce → consumer (Plans 1–4 already propagate; this slice proves it).

### O2a: Gateway (nginx) telemetry
**Covers:** OBS-EDGE-1, OBS-TRC-1 (edge hop), OBS-PIPE-1 (gateway health). **Detail design:** required. Directive names and the syslog/OTLP wiring are confirmed against the pinned image first.
- `nginx` runs the pinned official `-alpine-otel` image. It exports spans over OTLP to the Collector with `service.name=nginx-gateway`, continues an incoming `traceparent` and otherwise starts one, and forwards it to `polaris`, `polaris-assistant`, Keycloak and Grafana. Sampling is decided here once; the apps honour the parent's flag.
- Span names are the `location` route, never the raw URL, so span metrics stay low-cardinality.
- Access log is JSON with `trace_id`, `span_id`, `host`, route (`$uri`, no query string), method, status, `upstream_status`, request and upstream time, and bytes. It goes to the Collector over syslog and also stays on stdout. Authorization, cookies and OAuth `code`/`state` are never logged. Export loss must not block nginx.
- `stub_status` is on an internal-only listener, used by the compose healthcheck and scraped for connection metrics.
- SSE locations (`/mcp/`, `/api/v1/assistant`) are labelled so the latency SLI in O5 can exclude long-lived streams.
- Placing an order through `https://polaris.local` produces one trace whose root is the nginx span and whose child is `polaris`; the same `trace_id` is in the edge log in Loki. Stopping `polaris` produces edge `502`s that appear in the edge logs and span metrics. Stopping the Collector leaves nginx serving normally.

### O3: Redaction and cardinality guardrails
**Covers:** OBS-LOG-1, OBS-MET-1. **Detail design:** not needed.
- Collector `transform`/`redaction` processors remove or hash secrets, `Authorization`/cookie headers, and PII-shaped attributes (email, card-like numbers) on all three signals, before any exporter.
- A metric attribute allow-list drops `user_id` and unbounded business keys from metric data points; traces and logs keep them.
- Loki index labels are limited to the resource attributes already configured (`service.name`, `service.namespace`, `deployment.environment`); a limit on streams and labels is set.
- The redaction rules cover the edge access log too.
- Tests send known secrets and a high-cardinality attribute through the collector and assert they are absent from Loki, Tempo and Prometheus.

### O4: Dashboards as code
**Covers:** OBS-VIS-1, OBS-DIA-1. **Detail design:** not needed.
- A dashboard provider under `docker/telemetry/grafana/provisioning/dashboards/` loads versioned JSON, in folders per audience.
- Dashboards: **Edge (gateway)** (request rate, non-5xx ratio, p95 by route, upstream errors, connections), **Service RED** (per service, from span metrics and HTTP metrics), **JVM and runtime**, **Dependencies** (Postgres, Kafka, Keycloak, Redis as available), **Telemetry pipeline** (O1 metrics), **Traces and logs explorer** links. Each panel uses recorded units and template variables (`service`, `env`).
- A CI check validates that dashboard JSON parses, every datasource reference resolves, and no panel queries a forbidden label (O3).

### O5: SLOs, alerts and runbooks
**Covers:** OBS-SLO-1, OBS-PIPE-1 (alerting). **Detail design:** required. The SLI definitions and burn-rate windows are reviewed first.
- The edge gets its own availability and latency SLOs (the truest user-facing SLI, excluding SSE routes), in addition to the two applications.
- `docker/telemetry/prometheus/rules/` holds recording rules (SLIs) and multi-window burn-rate alerts for each tier-0 service (availability, latency), plus pipeline alerts (collector dropping, backend down, exporter queue near full).
- Alertmanager routes by severity and owner to Mailpit (SMTP) and a webhook; every alert has `summary`, `service`, `severity`, `dashboard_url` and `runbook_url` annotations.
- `promtool check rules` and `promtool test rules` run in CI, with unit tests for a fast burn, a slow burn and a no-alert case.
- `docs/operations/runbooks/` has one runbook per alert: what it means, first checks (the click-path from O2), mitigation, escalation. A test fails if an alert's `runbook_url` points to a missing file.
- Inducing failures (500s, added latency, Kafka stopped, collector stopped) fires the expected alert within its window and the email appears in Mailpit.

### O6: Async visibility (outbox and Kafka)
**Covers:** OBS-ASY-1. **Detail design:** required. Metric names and labels are a contract (Principle 2).
- **Bounded-context note:** the outbox library and Order are a different context from the Operations stack. This slice ships as two changes: (a) the app/library side publishes the metrics under an agreed name and label contract, (b) the platform side scrapes them and adds panels and alerts. Each is vertically complete and integrates only through the metric contract.
- Metrics: outbox pending count, oldest-pending age, publish success/failure; Kafka consumer-group lag and oldest-record age per group, from client metrics or a Kafka exporter.
- Alerts: outbox stuck, consumer lag growing, poison record blocked. Panels join on `event type`, not `orderNumber`.
- Stopping Kafka makes pending count and age rise and the alert fire; restarting drains them.

### O7: Access, retention and exposure
**Covers:** OBS-SEC-1. **Detail design:** not needed.
- Retention set and documented per signal (for example metrics 15d, traces 3d, logs 7d in dev), with disk-cap safeguards.
- Only Grafana is reachable through the gateway; backend ports are no longer published to the host (internal network only), except where a dev override documents why.
- Grafana roles come from Keycloak claims as today; datasource and folder permissions are provisioned, so viewers cannot edit datasources and the `explore` of logs is restricted to roles that need it.
- Grafana admin credentials and the OAuth client secret come from environment/secret files, not committed values (the compose file currently inlines `grafana-client-secret` and `admin/admin`).

### O8: End-to-end verification
**Covers:** all, as regression. **Detail design:** not needed.
- A script under `tests/e2e/` brings the stack up, places an order, then asserts through Grafana/Tempo/Loki/Prometheus HTTP APIs: a complete trace exists; its logs carry the same `trace_id`; span and service-graph metrics exist; the dashboards load; no secret or forbidden label is present.
- A failure-injection scenario verifies O1 (business unaffected), O5 (alert and email) and O6 (lag/outbox alert), then recovery.
- The operator walk-through from OBS-DIA-1 is recorded in `docs/operations/` with screenshots or queries.

## Open Questions

| # | Question | Needed by |
|:--|:--|:--|
| Q1 | Is a single-node Prometheus/Loki/Tempo the intended production shape, or is this a local/reference stack? It decides whether retention and HA are requirements or notes | O0, O7 |
| Q2 | Who receives pages, and where? Dev uses Mailpit; is there a real target to document? | O5 |

## Out of Scope

- Tenants in any form (dimensions, isolation, multi-tenant Loki/Tempo/Mimir), long-term object storage, and HA clustering of the backends.
- Replacing Prometheus with Mimir, or adding Pyroscope/profiling and Faro/RUM.
- Audit logging as a separate compliance stream (ADR-0016 covers agent tool audit).
- Grafana Cloud or any hosted backend.

## Definition of Done

- [ ] O0–O8 (including O2a) are `done`; every OBS requirement is verified by an automated test or a recorded manual step.
- [ ] `docker compose up` brings up a stack with provisioned datasources, dashboards, rules and routes, and no manual Grafana clicking.
- [ ] Inducing a failure produces an alert, an email in Mailpit, and a runbook link that resolves.
- [ ] A placed order can be followed from dashboard → trace → logs in the running stack.
- [ ] ADR-0020 is accepted; `docs/operations/` is no longer a placeholder.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
| 2026-09-30 | Initial draft | Improve the high-level requirements and implement them on the current Grafana stack | all |
| 2026-09-30 | Tenants dropped (none exist); tier-0 set to `polaris`, `polaris-assistant` and the `nginx` gateway; added O2a and OBS-EDGE-1, D6, D7 | Product answers; the gateway is the first hop and the only place edge failures are visible | O0, O2a, O3, O4, O5 |
