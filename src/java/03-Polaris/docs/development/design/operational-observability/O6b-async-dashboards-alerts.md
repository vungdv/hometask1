# Detail Design: O6b — Async dashboard, alerts and runbooks (platform side)

- **Plan:** [Operational Observability](../../plan/operational-observability.md), slice **O6**, part (b). **Covers:** OBS-ASY-1 (alerts and panels), OBS-SLO-1 (alert metadata).
- **Consumes only** the metric contract of [O6a](O6a-async-metrics-contract.md). No application or library code is touched.

## What was added

| Item | File |
|:--|:--|
| Dashboard `polaris-async` (outbox pending and age by `event_type`, publish success/failure, delivery lag, consumer lag, oldest in-flight age, skipped records) | `docker/telemetry/grafana/provisioning/dashboards/json/operations/polaris-async.json` |
| Recording rule `async:consumer_lag:records` and four alerts | `docker/telemetry/prometheus/rules/async.rules.yml` |
| promtool tests (fires and does not fire) | `docker/telemetry/prometheus/tests/async.test.yml` |
| Runbooks | `docs/operations/runbooks/{outbox-stuck,consumer-lag-growing,poison-record-blocked,async-metrics-missing}.md` |
| Live failure injection (test-only short window) | `scripts/telemetry/async-check.sh` |

Alertmanager needed no change: all alerts carry `owner=polaris` and `severity` of `page` or `ticket`, which the existing routing tree already covers.

## Scrape and naming (checked live)

The metrics arrive through the Collector's Prometheus exporter with the service in `exported_job` (`Polaris`, `Polaris-Fulfilment-Emulator`), as for the O4 dashboards; no relabelling is needed. The O5 normalisation to a lower-case `service` is for SLI series; these alerts instead set a static `service` label per alert family (`polaris-outbox`, `kafka-consumer`) so the page-inhibits-ticket rule (`equal: [service, sli]`) never hides one family behind another.

## Alerts

| Alert | Severity | Fires when | Absence and cold-start handling |
|:--|:--|:--|:--|
| `OutboxStuck` | page | oldest pending age > 300 s and backlog > 0, for 5 min | both series are always present (0 when empty); a stale age with no backlog does not fire |
| `ConsumerLagGrowing` | ticket | lag (dotted topic only) never at or below 100 in 10 min and still growing (deriv > 0), for 5 min | needs 10 minutes of samples, so a restart cannot fire it |
| `PoisonRecordBlocked` | ticket | oldest in-flight record age > 300 s and the group has lag > 0, for 5 min | see limitation below |
| `AsyncMetricsMissing` | ticket | outbox gauge, `order.shipments` age series or emulator age series absent, for 10 min | the Collector exporter keeps a dead app's series 5 min, so the alert waits about 15 min from the death of the app and cannot fire at a normal restart |

`polaris_kafka_consumer_records_skipped_records_total` exists only after the first skip, so no alert depends on it; the dashboard uses `or vector(0)`.

**Limitation of `PoisonRecordBlocked`** (known from the O6a review): the consumer clears its in-flight entry only on success or skip, so after a partition revoke or a container stop mid-record the age can keep growing. Requiring lag > 0 for the group and a live series tolerates the stop (series expire) and the typical revoke (lag series disappear), but a lingering lag series after a revoke can still raise a false ticket, and a blocked record whose lag reads 0 is missed. Fixing it properly is an application-side change (clear on revoke/stop) and would be a contract-compatible O6a follow-up.

## Verification

- `promtool check rules` and `promtool test rules` (`async.test.yml`: stuck, drained/resolved, stale age, growing lag vs flat/draining/underscore twin, poison fires vs revoked/blip/zero lag, metrics missing vs cold start) in `validate-configs.sh`.
- `alerts-validate.sh`: annotations, existing runbook per alert, dashboard uid provisioned, runbook README lists every alert.
- `validate-dashboards.sh --live` runs every panel query against Prometheus.
- `async-check.sh` (live): Kafka stopped, orders placed (accepted), pending count and age rise (per `event_type` too), `TestOutboxStuck_ShortWindow` (age > 20 s for 30 s, label `test="true"`, loaded from the git-ignored `rules-test/`) fires with an email in Mailpit; Kafka restarted, backlog and age drain to 0 and the alert resolves.
