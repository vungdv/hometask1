# Operations Documentation

How to run, observe, and recover Polaris in a live environment.

**Start here:** [Operational Observability: Requirements](high-level-requirement.md) (numbered `OBS-*` requirements, tiers, standards, data governance) and [ADR-0020](../technical/decisions/0020-observability-platform-on-the-grafana-stack.md) (platform choices). Implementation follows the [plan](../development/plan/operational-observability.md).

No operational runbooks exist yet. As they're written, organize them here by category:

- **Deployment** — how to build, configure, and deploy each app (`polaris`, `polaris-assistant`, gateway, Grafana/Keycloak stack).
- **Monitoring** — dashboards, alerts, and the traces/metrics/logs produced by the [observability architecture](../technical/decisions/).
- **Runbooks** — step-by-step procedures for known operational scenarios (e.g. rotating credentials, scaling a service, handling a stuck migration).
- **Recovery** — incident response and disaster recovery procedures (e.g. database restore, rollback a bad deploy).
