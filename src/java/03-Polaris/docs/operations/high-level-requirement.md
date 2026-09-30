# Operational Observability: Requirements

- **Status:** Draft, rewritten by slice O0 of the [operational observability plan](../development/plan/operational-observability.md)
- **Decision record:** [ADR-0020](../technical/decisions/0020-observability-platform-on-the-grafana-stack.md)
- **Design baseline:** [ADR-0016](../technical/decisions/0016-genai-observability-and-mcp-audit-standards.md), [ADR-0006](../technical/decisions/0006-grafana-domain-routing-and-tls-architecture.md)

## 1. Purpose and Scope

An operator MUST be able to go from an alert to a metric, a trace, a log and a root cause without leaving Grafana. This document turns that goal into numbered, testable requirements. Each requirement has a stable ID (`OBS-<AREA>-<n>`) that slices, tests and runbooks cite.

The key words MUST, MUST NOT, SHOULD and MAY are used as in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119). Every requirement carries an acceptance criterion that a script, a test or a recorded manual step can check.

The original eight high-level areas are kept as the intent of this document. Section 8 maps each to the requirement IDs below.

## 2. Standards

Polaris aligns with these standards and does not replace them with bespoke schemes (Principle 1 in [AGENTS.md](../../AGENTS.md)).

| Area | Standard |
|:--|:--|
| Telemetry model and naming | OpenTelemetry, with its semantic conventions (HTTP, DB, messaging, GenAI) |
| Telemetry transport | OTLP over HTTP or gRPC |
| Trace propagation | W3C Trace Context (`traceparent`, `tracestate`) |
| Messaging events | CloudEvents 1.0, Kafka protocol binding ([ADR-0019](../technical/decisions/0019-kafka-and-cloudevents-binding.md)) |
| Metrics and alert rules | Prometheus exposition format and Prometheus rule format |
| Error payloads | RFC 9457 / RFC 7807 Problem Details (API surface, unchanged) |

## 3. Component Tiers

Every production component declares one tier. The tier sets its instrumentation floor, SLO and alerting level.

| Tier | Components | Instrumentation floor | SLO | Alerting |
|:--|:--|:--|:--|:--|
| 0 | `polaris`, `polaris-assistant`, `nginx` (gateway) | RED metrics, traces, structured logs | Yes, with multi-window burn-rate alerts | Pages; each alert has a runbook |
| 1 | Platform dependencies they need: Postgres (`polaris-db` and other databases), Kafka (`kafka-1/2/3`), Keycloak, Redis. The telemetry stack: `otel-collector`, Tempo, Loki, Prometheus, Grafana and, from O5, Alertmanager | Health and dependency metrics. For the telemetry stack, the pipeline metrics of OBS-PIPE-1 | No | Availability alert |
| 2 | `polaris-fulfilment-emulator`, dev tooling (Mailpit, Swagger UI, `gcx-cli`) | Traces and logs only | No | None, no paging |

The gateway is tier-0 because it is the first hop of every request and the only place where edge failures (`502/503/504`, TLS, routing) are visible. Tier 1 and tier 2 membership is a default to be confirmed. The telemetry stack is tier 1 because its failure must not fail business requests (OBS-RES-1) but must be detected (OBS-PIPE-1).

## 4. Requirements

### 4.1 Standards and Visibility

| ID | Requirement | Acceptance criterion |
|:--|:--|:--|
| OBS-STD-1 | The platform MUST name its standards explicitly: OpenTelemetry and its semantic conventions, OTLP, W3C Trace Context, CloudEvents for messaging, and the Prometheus exposition and rule formats. | Section 2 lists each standard. Every later slice cites the standard it uses; a review finds no bespoke telemetry format. |
| OBS-VIS-1 | Every production component MUST declare a tier (0, 1 or 2) per section 3. Each tier MUST state its instrumentation floor, SLO and alerting level. | Section 3 lists every service in `docker-compose.yml` that ships in production with exactly one tier. Dashboards (O4) exist for every tier-0 component. |

### 4.2 Signals

| ID | Requirement | Acceptance criterion |
|:--|:--|:--|
| OBS-TRC-1 | 100% of inbound HTTP and Kafka entrypoints MUST continue an incoming `traceparent`. One business operation MUST be one trace across HTTP, outbox, Kafka and consumers. | An end-to-end test places an order and finds one trace holding the HTTP span, the outbox publish span, the Kafka produce span and each consumer span. A request sent with a known `traceparent` keeps that `trace_id`. |
| OBS-MET-1 | Each tier-0 service MUST export request rate, error rate and duration histograms, plus JVM and dependency metrics. Metrics for availability, capacity, retries, queues and dependencies SHOULD be collected where the component has them. Metrics MUST NOT carry `user_id` or `order_*` labels. A per-metric series cap MUST be stated and checked. | A query for the named metrics returns data for each tier-0 service. A test sends a high-cardinality attribute and finds no such label in Prometheus. The stated cap is asserted by a check. |
| OBS-LOG-1 | Logs MUST be JSON with `trace_id`, `span_id`, `service.name` and business keys. Logs MUST NOT be written to protocol `stdout`. Secrets and PII MUST be redacted before storage. | A log query in Loki returns records that all carry the four keys. A test sends known secrets and PII-shaped values and finds none in Loki. |
| OBS-EDGE-1 | The gateway MUST start (or continue) the trace for every inbound request and propagate `traceparent` upstream. Its JSON access log MUST carry `trace_id`, `span_id`, route, status and upstream status, and MUST NOT carry a query string, an `Authorization` header or a cookie. Edge availability (non-5xx ratio) and latency MUST be SLIs. An upstream `502/503/504` MUST be visible at the edge. | An order placed through `https://polaris.local` gives one trace rooted at the gateway span with `polaris` as a child, and the same `trace_id` is in the edge log in Loki. Stopping `polaris` produces edge `502` entries in the edge log and in span metrics. A test finds no query string, `Authorization` value or cookie in the edge log. |
| OBS-ASY-1 | Outbox pending count, oldest-pending age, publish failures, and per-group Kafka consumer lag and oldest-record age MUST be measurable and alertable. | Each metric is queryable in Prometheus. Stopping Kafka raises pending count and age and fires the alert; restarting drains them. |

### 4.3 Objectives, Alerts and Diagnosis

| ID | Requirement | Acceptance criterion |
|:--|:--|:--|
| OBS-SLO-1 | Each tier-0 service MUST have SLIs and SLOs. Alerts MUST use multi-window burn rates. Each SLO MUST have an error budget. Each alert MUST carry service, severity, dashboard link and `runbook_url`. | `promtool check rules` and `promtool test rules` pass, with unit tests for a fast burn, a slow burn and a no-alert case. A test fails if any alert lacks the four fields or its `runbook_url` points to a missing file. |
| OBS-DIA-1 | From an alert, a documented click-path MUST reach the metric panel, an exemplar trace and the correlated logs, in at most 4 steps. Operators MUST be able to identify the affected service, route, operation and business transaction (by `orderNumber` in traces and logs, never in metrics). | The click-path is written in the runbooks and walked once against the running stack, with the step count recorded. It is at most 4. |

### 4.4 Resilience, Security and Pipeline Health

| ID | Requirement | Acceptance criterion |
|:--|:--|:--|
| OBS-RES-1 | Stopping any telemetry backend or the collector MUST NOT fail or slow business requests. Export MUST be asynchronous, bounded, and MUST drop data when full. | While `tempo`, `loki` or `otel-collector` is stopped under a k6 or curl load, business request status and latency stay within the stated tolerance of the baseline. Telemetry resumes without a restart when the backend returns. |
| OBS-SEC-1 | Telemetry access MUST be role-based (Keycloak). Retention MUST be stated and configured per signal. Backends MUST NOT be exposed except through the gateway. Telemetry transport SHOULD be encrypted (TLS) where it leaves a host. Redaction MUST be enforced in the collector. | Grafana roles map from Keycloak claims and a viewer cannot edit datasources. Retention settings exist for metrics, traces and logs and match section 6. A port scan of the host finds no published backend port outside a documented dev override. A test shows the collector removes seeded secrets. |
| OBS-PIPE-1 | The telemetry pipeline MUST be monitored: collector accepted, refused and dropped data, exporter queue and failures, backend up, and ingest lag. It MUST alert on its own failure. | The metrics are queryable in Prometheus. Stopping the collector or a backend fires the pipeline alert within its window. |

## 5. Non-Goals and Assumptions

Non-goals, carried from the plan:

- Tenants in any form: tenant dimensions, isolation, multi-tenant Loki, Tempo or Mimir. Polaris has no tenants today (confirmed 2026-09-30). Revisit if tenants are introduced.
- Long-term object storage and HA clustering of the backends.
- Replacing Prometheus with Mimir, and adding Pyroscope profiling or Faro/RUM.
- Audit logging as a separate compliance stream. [ADR-0016](../technical/decisions/0016-genai-observability-and-mcp-audit-standards.md) covers agent tool audit.
- Grafana Cloud or any hosted backend.

Open questions, carried forward unresolved. No answer is assumed here:

| # | Question | Effect until answered |
|:--|:--|:--|
| Q1 | Is a single-node Prometheus, Loki and Tempo the intended production shape, or is this a local or reference stack? | Retention values in section 6 are dev defaults, not production commitments. HA is not a requirement (see the non-goals). Slice O7 must state this again when it configures retention. |
| Q2 | Who receives pages, and where? | In dev, alerts go to Mailpit over SMTP plus a webhook receiver. No production notification target is documented or required. Slice O5 must not invent one. |

## 6. Data Governance

- **PII and secrets.** Telemetry MUST NOT hold secrets (tokens, passwords, `Authorization` headers, cookies, OAuth `code` and `state`) or PII (email, card-like numbers). The collector redacts or hashes them on all three signals before any exporter (OBS-LOG-1, OBS-SEC-1). Raw GenAI prompts and completions stay out of span attributes, as [ADR-0016](../technical/decisions/0016-genai-observability-and-mcp-audit-standards.md) already requires.
- **Cardinality.** Metrics MUST NOT use `user_id` or `order_*` labels (OBS-MET-1). Traces and logs MAY keep business keys. Loki index labels are limited to `service.name`, `service.namespace` and `deployment.environment`.
- **Retention.** Each signal has a stated retention that is configured, not left unbounded (OBS-SEC-1). Dev defaults, subject to Q1: metrics 15 days, traces 3 days, logs 7 days, with disk-cap safeguards.
- **Access.** Telemetry is readable only through Grafana, by role, from Keycloak claims. Grafana admin credentials and the OAuth client secret come from the environment or secret files, never from committed values.

## 7. Traceability

| Requirement | Slices |
|:--|:--|
| OBS-STD-1, OBS-VIS-1 | O0 (document); O4 (dashboards per tier) |
| OBS-TRC-1 | O2 (verification), O2a (edge hop) |
| OBS-MET-1, OBS-LOG-1 | O3 |
| OBS-EDGE-1 | O2a |
| OBS-ASY-1 | O6 |
| OBS-SLO-1 | O5 |
| OBS-DIA-1 | O2, O4 |
| OBS-RES-1 | O1 |
| OBS-SEC-1 | O7 |
| OBS-PIPE-1 | O1, O2a (gateway health), O5 (alerting) |
| All | O8 (end-to-end regression) |

## 8. Mapping from the Original Eight Areas

| # | Original area | Requirements |
|:--|:--|:--|
| 1 | Operational visibility | OBS-VIS-1, OBS-EDGE-1 |
| 2 | Distributed observability | OBS-STD-1, OBS-TRC-1 |
| 3 | Metrics | OBS-MET-1 (tenant dimension dropped, D4) |
| 4 | Structured logging | OBS-LOG-1 |
| 5 | Asynchronous processing | OBS-ASY-1, OBS-TRC-1 |
| 6 | Alerting and SLOs | OBS-SLO-1 |
| 7 | Diagnosis and operations | OBS-DIA-1 (tenant identification dropped, D4) |
| 8 | Resilience and security | OBS-RES-1, OBS-SEC-1 (tenant isolation dropped, D4), OBS-PIPE-1 |
