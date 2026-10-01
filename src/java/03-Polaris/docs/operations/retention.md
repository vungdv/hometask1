# Telemetry retention (OBS-SEC-1)

**Scope (Q1).** Q1 (is a single-node Prometheus/Loki/Tempo the intended production shape?) is **unanswered**. This stack is therefore treated and documented as a **dev/reference stack**: single node, local-filesystem storage, no HA, no long-term object storage (all out of scope in the plan). The numbers below are dev defaults. If Q1 resolves to "production", retention, disk sizing, replication and disk-full alerting become requirements and need a new plan slice.

## Retention per signal

| Signal | Backend | Retention | Where it is set | Disk-cap safeguard |
|:--|:--|:--|:--|:--|
| Metrics | Prometheus | **15 days** | `--storage.tsdb.retention.time=15d` in `docker-compose.override.yml` | `--storage.tsdb.retention.size=5GB`: whichever limit is hit first deletes the oldest blocks |
| Traces | Tempo | **3 days** | `backend_scheduler.provider.compaction.compaction.block_retention: 72h` and `backend_worker.compaction.block_retention: 72h` in `docker/telemetry/tempo/tempo.yml` (Tempo 3.x enforces retention in the backend scheduler/worker; the 3.0 default is 14 days) | Tempo has no size cap. Ingest is bounded instead: `overrides.defaults.ingestion.rate_limit_bytes` 15 MB/s (burst 20 MB), `global.max_bytes_per_trace` 5 MB |
| Logs | Loki | **7 days** | `limits_config.retention_period: 168h` with `compactor.retention_enabled: true` in `docker/telemetry/loki/loki.yml` | Loki has no size cap. Ingest is bounded: `ingestion_rate_mb` 8 (burst 16), `per_stream_rate_limit` 3 MB (burst 15 MB), plus the O3 stream and label limits |
| Alerts/notifications | Alertmanager | Its own default data retention (120 h) in the `alertmanager-data` volume | defaults | n/a |

Behaviour at the limits: the Collector's bounded `sending_queue` retries, then drops, and never blocks the applications (OBS-RES-1). Rejected or dropped data shows in the telemetry pipeline dashboard and the `TelemetryCollectorDropping` / `TelemetryCollectorRefusing` alerts.

Retention is enforced lazily. Prometheus removes whole blocks (up to roughly 2 hours late); Loki deletes expired chunks after the compactor's `retention_delete_delay` (2 h, compaction every 10 min); Tempo runs retention hourly. So data slightly older than the stated period may remain for a few hours.

## What is NOT covered (dev stack)

- No disk-full alert on the Docker volumes (`prometheus-data`, `loki-data`, `tempo-data`). Volumes live on the Docker host's disk; size it for the worst case: Prometheus at most about 5 GB, Tempo and Loki bounded only by the rate caps above multiplied by their retention (in practice far less at dev traffic).
- No replication or backup. `make clean` deletes all telemetry.
- Changing retention does not rewrite existing data: shortening deletes the older part on the next cycle, lengthening cannot restore deleted data.

## Verify on a running stack

`scripts/telemetry/validate-configs.sh` asserts the configured values. To read the effective values from the live backends (inside the compose network, no host ports):

```bash
docker exec grafana curl -s http://prometheus:9090/api/v1/status/flags | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d["storage.tsdb.retention.time"], d["storage.tsdb.retention.size"])'
docker exec grafana curl -s http://tempo:3200/status/config | grep -E 'block_retention'
docker exec grafana curl -s http://loki:3100/config | grep -E 'retention_period|retention_enabled'
```

Expected: `15d 5GiB` (Prometheus reads `5GB` as 5 GiB), `block_retention: 72h` (scheduler and worker), `retention_period: 1w` (the 7 days; a second `0s` line is the per-tenant default override) and `retention_enabled: true`.

## How to change a value

Edit the file named in the table, run `scripts/telemetry/validate-configs.sh` (it parses the config with the pinned image and fails if the policy above is violated), then `docker compose up -d <service>`. After recreating a backend, restart the gateway once (`docker compose restart nginx`): nginx resolves its upstream names at start and keeps the old container address ([exposure](exposure.md)).
