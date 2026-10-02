# Verification: OBS requirement traceability and end-to-end runs

Plan slice O8. Every `OBS-*` requirement in [high-level-requirement.md](high-level-requirement.md) maps below to at least one automated live check or a recorded manual step. `tests/e2e/observability/traceability-check.sh` fails when a requirement ID has no row, when a row names a script that does not exist, or when a manual step points to a missing document or heading, so the table cannot rot silently.

All live checks need the running compose stack (`make up`) and are **not run in CI**; static ones (`validate-*`, `alerts-validate`, promtool) run from `scripts/telemetry/validate-configs.sh`. Backends are not published to the host (OBS-SEC-1), so the scripts call them from inside `polaris-net` (`scripts/telemetry/lib/net.sh`) or, with `make up-dev-ports`, on loopback (`TELEMETRY_ACCESS=host`).

## Mapping table

Format (parsed by the checker): `| ID | kind | check | note |`. `auto` rows name a script under the repo root; `manual` rows name `path#heading-anchor`.

| Requirement | Kind | Check | What it proves |
|:--|:--|:--|:--|
| OBS-STD-1 | manual | docs/operations/high-level-requirement.md#2-standards | Standards are named in section 2; every later slice cites its standard (review step, recorded here) |
| OBS-STD-1 | auto | scripts/telemetry/validate-configs.sh | Collector, Prometheus, Tempo, Loki and Alertmanager configs validate with the pinned images and promtool |
| OBS-VIS-1 | manual | docs/operations/high-level-requirement.md#3-component-tiers | Tier table covers every shipped component (review step) |
| OBS-VIS-1 | auto | scripts/telemetry/validate-dashboards.sh | Dashboards per tier are valid, provisioned, and every panel query runs (`--live`) |
| OBS-VIS-1 | auto | tests/e2e/observability/run.sh | Every provisioned dashboard loads through the Grafana API on the live stack |
| OBS-TRC-1 | auto | tests/e2e/observability/run.sh | One order is one trace: nginx root, HTTP, outbox publish, Kafka produce, consumer |
| OBS-TRC-1 | auto | scripts/telemetry/correlation-check.sh | traceparent continued, trace-to-logs, exemplars, order trace |
| OBS-MET-1 | auto | scripts/telemetry/redaction-check.sh | `user_id` and `order_*` are absent from metric labels; per-metric series cap |
| OBS-MET-1 | auto | tests/e2e/observability/run.sh | No forbidden label or series on the live Prometheus; Loki labels within the allow-list |
| OBS-LOG-1 | auto | scripts/telemetry/redaction-check.sh | Known secrets and PII never reach Loki, Tempo or Prometheus |
| OBS-LOG-1 | auto | tests/e2e/observability/run.sh | The order's logs carry the same `trace_id`; no bearer token or password in trace, logs or edge logs |
| OBS-EDGE-1 | auto | scripts/telemetry/edge-check.sh | nginx root span, JSON access log fields, no query string or secrets, edge 5xx visible (`RUN_FAILURE=1`) |
| OBS-ASY-1 | auto | scripts/telemetry/async-check.sh | Kafka stop raises pending count and age, alert and email, drain on restart, metrics continuity |
| OBS-ASY-1 | auto | tests/e2e/observability/failure-injection.sh | The same scenario composed into the O8 failure run |
| OBS-SLO-1 | auto | scripts/telemetry/alerts-validate.sh | Every alert has service, severity, dashboard_url and a runbook_url that resolves to an existing file |
| OBS-SLO-1 | auto | scripts/telemetry/alerts-check.sh | Live: burn-rate alert (shortened test windows) fires, email in Mailpit, webhook |
| OBS-DIA-1 | manual | docs/operations/operator-walkthrough.md#click-path-alert-to-business-transaction | The four-step click-path, with queries and API evidence recorded from the live stack |
| OBS-DIA-1 | auto | tests/e2e/observability/walkthrough-check.sh | Runs the four walk-through hops through Grafana's datasource proxy: alert dashboard, exemplar, trace logs by trace_id, order number to trace |
| OBS-DIA-1 | auto | tests/e2e/observability/run.sh | Trace, logs by trace_id, exemplars and dashboards the walk-through relies on exist |
| OBS-RES-1 | auto | scripts/telemetry/resilience-check.sh | tempo, loki and collector stopped under load: status 200, bounded latency, telemetry resumes |
| OBS-RES-1 | auto | tests/e2e/observability/failure-injection.sh | The same, composed with alerts and async and with guaranteed restoration |
| OBS-SEC-1 | auto | scripts/telemetry/access-check.sh | No published backend ports, secrets from `.env`, Keycloak role mapping on real logins |
| OBS-SEC-1 | manual | docs/operations/retention.md#verify-on-a-running-stack | Retention per signal is stated and configured; verification steps recorded |
| OBS-PIPE-1 | auto | scripts/telemetry/alerts-check.sh | Collector or backend stopped fires `TelemetryCollectorDown` / `TelemetryBackendDown` with email |
| OBS-PIPE-1 | auto | scripts/telemetry/validate-dashboards.sh | The pipeline dashboard queries run against the live metrics |

## Running everything

| Goal | Command | Typical duration |
|:--|:--|:--|
| Traceability only (static, no stack) | `tests/e2e/observability/traceability-check.sh` | seconds |
| Healthy-stack end-to-end | `tests/e2e/observability/run.sh` | about 2 minutes (it composes five existing checks; `SKIP_CHECKS=1` for about 1 minute) |
| Operator walk-through queries | `tests/e2e/observability/walkthrough-check.sh` | seconds |
| Failure injection and recovery | `tests/e2e/observability/failure-injection.sh` | 30 to 60 minutes; stops containers, always restores them (trap) |
| Fresh clone bootstrap | `tests/e2e/observability/fresh-bootstrap.sh --yes-delete-all-volumes` | **destructive**, see below |

### Failure injection

`failure-injection.sh` composes `resilience-check.sh` (O1: business status 200 and bounded latency while `tempo`, `loki` and `otel-collector` are stopped, then telemetry resumes), `alerts-check.sh` (O5: alert fires in Alertmanager, email in Mailpit, webhook; burn, backend, gateway and collector scenarios) and `async-check.sh` (O6: Kafka stopped, outbox backlog and age rise, `OutboxStuck` test alert fires, drain on recovery), each under a hard time bound. An `EXIT` trap starts every container it may have stopped and waits for health, then a final `run.sh` proves a placed order still yields a complete trace. `STEPS="resilience alerts async"` selects phases.

### Fresh clone bootstrap (opt-in, destructive)

`fresh-bootstrap.sh` runs `make clean && make up`, which **deletes every volume** of the compose stack (databases, Keycloak realm, Grafana, Prometheus, Tempo, Loki data), then checks that `.env` was generated by `scripts/init-env.sh`, that the master realm import substituted `GRAFANA_OAUTH_CLIENT_SECRET` into the `grafana` client (a Grafana SSO login works) and that `run.sh` passes on the empty stack. It refuses to run without `--yes-delete-all-volumes` and an interactive `DELETE` confirmation (or `CONFIRM_DELETE=DELETE` for automation). Run it only on a disposable checkout or when losing local data is acceptable.

## Manual steps recorded

| Step | Where | Who and when |
|:--|:--|:--|
| Standards named and cited (OBS-STD-1) | [requirements section 2](high-level-requirement.md#2-standards), [ADR-0020](../technical/decisions/0020-observability-platform-on-the-grafana-stack.md) | Reviewed in the O0 PR |
| Tier table covers every component (OBS-VIS-1) | [requirements section 3](high-level-requirement.md#3-component-tiers) | Reviewed in the O0 PR |
| Operator click-path (OBS-DIA-1) | [operator-walkthrough.md](operator-walkthrough.md) | Verified on the live stack in the O8 PR |
| Retention values (OBS-SEC-1) | [retention.md](retention.md) | Verified in the O7 PR |
