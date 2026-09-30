# ADR-0020: Observability Platform on the Grafana Stack

* **Status:** Proposed (2026-09-30, Plan Operational Observability O0)
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Date:** 2026-09-30
* **Technical Story:** Polaris already runs an OpenTelemetry Collector, Tempo, Loki, Prometheus and Grafana. The operational observability plan makes that stack meet numbered requirements. This ADR fixes the platform choices the slices depend on.
* **Requirement Reference:** [Operational Observability: Requirements](../../operations/high-level-requirement.md)
* **Plan Reference:** [Plan: Operational Observability on the Grafana Stack](../../development/plan/operational-observability.md) (D1–D5)
* **Builds on:** [ADR-0016](0016-genai-observability-and-mcp-audit-standards.md), [ADR-0006](0006-grafana-domain-routing-and-tls-architecture.md)

---

## 1. Context and Problem Statement

Apps send OTLP/HTTP to `otel-collector`, which feeds Tempo (traces), Loki (logs) and a Prometheus exporter (metrics). Grafana reads all three. There are no correlation links, no span metrics, no alerts and no stated tenancy or notification target. Five choices decide the shape of the work.

**Which components carry the Polaris observability platform, and how are they used?**

---

## 2. Decision Drivers

* **Standards first ([AGENTS.md Principle 1](../../../AGENTS.md)):** OpenTelemetry, OTLP, Prometheus rule format. No bespoke agents or formats.
* **Walkable architecture:** as few components as possible between an app and Grafana.
* **Testable as files:** rules and configuration are provisioned from version control and checked in CI.
* **Scope honesty:** do not build for tenants that do not exist.

---

## 3. Considered Options and Decision Outcome

### 3.1 D1: Collector

| Option | Assessment |
|:--|:--|
| Add Grafana Alloy | Adds a component with no new capability here |
| **Keep the OpenTelemetry Collector** | **Chosen.** It is the OpenTelemetry standard and is already wired |

### 3.2 D2: Alerting

| Option | Assessment |
|:--|:--|
| Grafana-managed alerting | Rules live in Grafana's model, and are harder to unit test |
| **Prometheus rule files plus Alertmanager** | **Chosen.** Standard PromQL, unit-testable with `promtool`, provisioned as files. Grafana displays the rules. Costs one new container |

### 3.3 D3: Span-derived metrics

| Option | Assessment |
|:--|:--|
| Collector `spanmetrics` connector | A second place that computes span metrics |
| **Tempo `metrics_generator`** (span metrics and service graph), remote-writing to Prometheus | **Chosen.** One place, and it feeds the Grafana service map |

### 3.4 D4: Tenancy

**Tenant dimensions and multi-tenant backends are out of scope.** Polaris has no tenants today (confirmed 2026-09-30). The requirement drops its tenant wording. Revisit this decision if tenants are introduced.

### 3.5 D5: Alert notification target in dev

**Mailpit** (already in the stack) over SMTP, plus a webhook receiver to prove routing. A production target is an open question, carried in the requirements document (Q2), and is not decided here.

---

## 4. Consequences

### Positive
* No new telemetry agent. The pipeline stays walkable: app, Collector, backend, Grafana.
* Alert rules are plain files that CI checks with `promtool`.
* Span metrics and the service map come from one component.

### Negative / Operational Safeguards
* One more container (Alertmanager) to run and monitor. The pipeline alerts of OBS-PIPE-1 cover it.
* Tempo `metrics_generator` requires Prometheus to enable its remote-write receiver and exemplar storage (slice O2).
* Rules cannot use the Grafana-managed alert UI features. Grafana displays them read-only.
* Whether a single-node backend is the production shape (Q1) is not decided here. Retention and HA claims stay dev defaults until it is.

---

## 5. Related Decisions

The plan's D6 (tier-0 components) and D7 (gateway telemetry via the `-alpine-otel` nginx image) are recorded in the requirements document and the plan, and are not part of this ADR.
