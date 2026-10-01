# Operations Documentation

How to run, observe, and recover Polaris in a live environment.

**Start here:** [Operational Observability: Requirements](high-level-requirement.md) (numbered `OBS-*` requirements, tiers, standards, data governance) and [ADR-0020](../technical/decisions/0020-observability-platform-on-the-grafana-stack.md) (platform choices). Implementation follows the [plan](../development/plan/operational-observability.md).

Alert runbooks live in [runbooks/](runbooks/README.md), one per Prometheus alert, with the alert-to-trace-to-logs click-path. SLO, alert and notification design: [O5 detail design](../development/design/operational-observability/O5-slos-alerts-runbooks.md). Dev notification targets: Mailpit and Alertmanager UIs are internal to the compose network; `make up-dev-ports` publishes them on loopback (`http://localhost:8025`, `http://localhost:9093`), see [exposure](exposure.md). The production page target (Q2) is not decided.

Telemetry security (OBS-SEC-1), all Q1-scoped to the single-node dev/reference stack:

- [Retention](retention.md): metrics 15d (+5GB cap), traces 3d, logs 7d, ingest caps, how to verify.
- [Exposure](exposure.md): which ports are published (none for backends), the opt-in `docker-compose.dev-ports.yml`, how scripts reach the backends.
- [Access](access.md): Keycloak roles to Grafana roles, datasource and folder permissions as code, secrets bootstrap and rotation, operator guides, the Grafana OSS limits.

Verification (plan O8):

- [Verification](verification.md): every `OBS-*` requirement mapped to an automated check or a recorded manual step (`tests/e2e/observability/traceability-check.sh` fails on a gap), plus how to run the end-to-end, failure-injection and (opt-in, destructive) fresh-bootstrap scripts.
- [Operator walk-through](operator-walkthrough.md): alert to metric panel, exemplar trace, logs and business transaction in 4 steps, with the queries verified on the live stack.

Other operational documents are organised here by category as they are written:

- **Deployment** — how to build, configure, and deploy each app (`polaris`, `polaris-assistant`, gateway, Grafana/Keycloak stack).
- **Monitoring** — dashboards, alerts, and the traces/metrics/logs produced by the [observability architecture](../technical/decisions/).
- **Runbooks** — step-by-step procedures for known operational scenarios (e.g. rotating credentials, scaling a service, handling a stuck migration).
- **Recovery** — incident response and disaster recovery procedures (e.g. database restore, rollback a bad deploy).
