# Operations Documentation

How to run, observe, and recover Polaris in a live environment.

**Start here:** [Operational Observability: Requirements](high-level-requirement.md) (numbered `OBS-*` requirements, tiers, standards, data governance) and [ADR-0020](../technical/decisions/0020-observability-platform-on-the-grafana-stack.md) (platform choices). Implementation follows the [plan](../development/plan/operational-observability.md).

Alert runbooks live in [runbooks/](runbooks/README.md), one per Prometheus alert, with the alert-to-trace-to-logs click-path. SLO, alert and notification design: [O5 detail design](../development/design/operational-observability/O5-slos-alerts-runbooks.md). Dev notification targets: Mailpit `http://localhost:8025`, Alertmanager `http://localhost:9093` (loopback only). The production page target (Q2) is not decided.

Other operational documents are organised here by category as they are written:

- **Deployment** — how to build, configure, and deploy each app (`polaris`, `polaris-assistant`, gateway, Grafana/Keycloak stack).
- **Monitoring** — dashboards, alerts, and the traces/metrics/logs produced by the [observability architecture](../technical/decisions/).
- **Runbooks** — step-by-step procedures for known operational scenarios (e.g. rotating credentials, scaling a service, handling a stuck migration).
- **Recovery** — incident response and disaster recovery procedures (e.g. database restore, rollback a bad deploy).
