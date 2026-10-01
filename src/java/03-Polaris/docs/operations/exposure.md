# Telemetry exposure (OBS-SEC-1)

**Rule.** Telemetry backends are **not** exposed except through the gateway. The base stack publishes no backend port to the host; the only telemetry UI a person opens is Grafana at `https://grafana.polaris.local`, behind the nginx gateway and Keycloak login ([access](access.md)). Dev/reference stack (Q1 unanswered): everything is plain HTTP on the private `polaris-net` compose network; nothing leaves the host, so TLS between containers is not configured (OBS-SEC-1 says SHOULD where telemetry leaves a host; revisit if Q1 turns production).

## Port map

| Component | Container port | Base stack (`docker compose up`) | With `docker-compose.dev-ports.yml` |
|:--|:--|:--|:--|
| Grafana | 3000 | internal; reached via the gateway `https://grafana.polaris.local` | internal (still only via the gateway) |
| Prometheus | 9090 | internal | `127.0.0.1:9090` |
| Loki | 3100 | internal | `127.0.0.1:3100` |
| Tempo | 3200 (API), 4317/4318 (OTLP, from the Collector only) | internal (the former `4319` host publish is removed) | `127.0.0.1:3200` |
| OTel Collector | 4317 (OTLP gRPC), 4318 (OTLP HTTP), 8889 (Prometheus exporter), 8888 (self-metrics), 5514/udp (syslog) | internal | `127.0.0.1:4317`, `127.0.0.1:4318` (8889 is not published; Prometheus scrapes it internally) |
| Alertmanager | 9093 | internal | `127.0.0.1:9093` |
| Mailpit UI / SMTP | 8025 / 1025 | internal | `127.0.0.1:8025` (SMTP never published) |
| Alert webhook | 8080 | internal | never published |
| nginx gateway | 80, 443 | published (the single entrypoint) | published |

Outside the telemetry stack and unchanged by this slice: Kafka brokers on `127.0.0.1:9094-9096` (loopback, for `make run`), and `polaris` `8080` / `polaris-assistant` `8081`, which the base compose still publishes on all interfaces. Closing those two is a separate change (the correlation check and `make test-perf` use `localhost:8080`).

## Why a dev-ports override exists

Host-side tooling needs a few loopback ports, so they are an explicit opt-in rather than the default:

- **Apps started with `make run`** export OTLP to `http://localhost:4318` (their built-in default). Inside compose the apps use `otel-collector:4318`, so no port is needed there.
- **Alertmanager and Mailpit UIs** for reading alert emails in a browser (the links in alert emails point at `localhost:9093`).
- **Ad-hoc curl against Prometheus/Loki/Tempo** from the host.

Use: `make up-dev-ports` (equivalent to `docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.dev-ports.yml up -d --build`). Everything is bound to `127.0.0.1`, never `0.0.0.0`. Do not use it on a shared host. Going back: `make up` recreates the containers without the ports.

## How scripts and docs reach the backends

The `scripts/telemetry/*-check.sh` scripts source `scripts/telemetry/lib/net.sh`, which runs `curl` **inside the `grafana` container** (it is on `polaris-net`, ships curl, and resolves `prometheus`, `loki`, `tempo`, `alertmanager`, `mailpit`, `otel-collector` by name). So every check works on the base stack with no published port. Set `TELEMETRY_ACCESS=host` to use plain host curl against the dev-ports loopback instead. The per-backend `PROM`, `LOKI`, `TEMPO`, `AM`, `MAILPIT`, `OTLP` environment overrides still win. `validate-dashboards.sh --live` does the same by default; pass a URL to query a host-published Prometheus.

An ad-hoc query without the override:

```bash
docker exec grafana curl -s 'http://prometheus:9090/api/v1/query?query=up'
```

## Verify

`scripts/telemetry/access-check.sh` asserts on the running stack that no backend publishes a port (or that, with the override, only `127.0.0.1` does) and that the host cannot connect to `3000 3100 3200 4317 4318 4319 8889 9090 9093 8025`. `scripts/telemetry/validate-configs.sh` asserts the same statically from the compose files.

## Operational gotcha: restart the gateway after recreating a backend

nginx resolves upstream names (`grafana`, `keycloak`, `polaris`) once at start. After `docker compose up -d` recreates one of them (a config or environment change), the new container has a new address and the gateway answers `502` until it is restarted: `docker compose restart nginx`. This predates O7; it surfaced because O7 changes Grafana's and Keycloak's environment.
