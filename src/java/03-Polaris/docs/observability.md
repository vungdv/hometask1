# Observability & Telemetry (LGTM Stack)

Polaris includes a production-grade local telemetry stack adhering to OpenTelemetry (OTel) standards for distributed tracing, metrics, and correlated structured logging.

---

## Architecture Overview

```mermaid
flowchart LR
    subgraph Browser["User Agent"]
        User["Browser / Client"]
    end

    subgraph Security["Identity & Access"]
        Keycloak["Keycloak<br/>(https://id.polaris.local)"]
        Nginx["Nginx TLS Proxy<br/>(:443)"]
    end

    subgraph Apps["Applications"]
        Polaris["Polaris Backend<br/>(Spring Boot)"]
    end

    subgraph Collector["Telemetry Collection"]
        OTel["OpenTelemetry Collector<br/>(:4317 gRPC / :4318 HTTP)"]
    end

    subgraph Storage["Telemetry Storage"]
        Tempo["Tempo<br/>(Traces :3200)"]
        Loki["Loki<br/>(Logs :3100)"]
        Prometheus["Prometheus<br/>(Metrics :9090)"]
    end

    subgraph UI["Visualization & CLI"]
        Grafana["Grafana<br/>(:3000)"]
        GCX["GCX CLI<br/>(Grafana CLI)"]
    end

    User -->|1. Auth Redirect| Keycloak
    Keycloak -->|2. Auth Code| User
    User -->|3. Callback :3000| Grafana
    Grafana -->|4. Back-channel Token Exchange| Nginx
    Nginx -->|Proxy :8080| Keycloak

    Polaris -->|OTLP Traces, Metrics, Logs :4318| OTel
    Keycloak -->|OTLP Traces :4317| OTel
    OTel -->|Traces| Tempo
    OTel -->|Logs| Loki
    Prometheus -->|Scrape :8889| OTel

    Grafana -->|Query| Tempo
    Grafana -->|Query| Loki
    Grafana -->|Query| Prometheus
    GCX -->|API Basic Auth| Grafana
```

---

## Telemetry Components

### 1. Metrics (Prometheus & OpenTelemetry)
- **Polaris Export**: The Spring Boot backend emits runtime, HTTP, and JVM metrics using OpenTelemetry to `otel-collector:4318/v1/metrics`.
- **Scraping**: Prometheus scrapes processed metrics from `otel-collector:8889`.
- **Access**:
  - Prometheus UI: [http://localhost:9090](http://localhost:9090)
  - Grafana Metrics Dashboards: [http://localhost:3000](http://localhost:3000)

### 2. Distributed Tracing (Tempo)
- **End-to-End Tracing**: Polaris and Keycloak trace transactions end-to-end using standard W3C Trace Context (`traceparent`).
- **Keycloak Traces**: Keycloak exports gRPC OTLP traces to `otel-collector:4317` (`KC_TRACING_ENABLED=true`).
- **Polaris Traces**: Polaris forwards traces via HTTP OTLP to `otel-collector:4318/v1/traces` (100% sample rate in development).
- **Tempo Storage**: OTel Collector batches and delivers traces to Tempo (`tempo:4317`).
- **Inspection**: Traces can be queried and analyzed in Grafana via the Tempo datasource.

### 3. Correlated Logging (Loki)
- **Structured Logs**: Polaris forwards application logs with correlated `trace_id` and `span_id` metadata to `otel-collector:4318/v1/logs`, forwarded into Loki (`http://loki:3100/otlp`).
- **Log Seeding Utility**: A standalone `logseeding` container is provided to generate sample log volume:
  ```bash
  # Send a batch of 50 sample logs
  docker compose exec logseeding python logseeding.py --url http://loki:3100 --count 50

  # Continuously stream sample logs
  docker compose exec logseeding python logseeding.py --url http://loki:3100 --stream --interval 1.0
  ```

### 4. Grafana SSO & Observability Visualization
- **Grafana Web Dashboard**: Accessible at [http://localhost:3000](http://localhost:3000).
  - Pre-provisioned datasources: Prometheus (default), Loki, and Tempo.
- **Keycloak SSO Authentication (OIDC / OAuth2 Generic)**:
  - **Protocol & Standard**: Standard OpenID Connect 1.0 Authorization Code Flow adhering to AGENTS.md Principle 1.
  - **Client ID**: `grafana` (Confidential client).
  - **Front-Channel Flow**: Browser initiates login, redirects to Keycloak at `https://id.polaris.local/realms/polaris/protocol/openid-connect/auth`, and returns with authorization code to `http://localhost:3000/login/generic_oauth`.
  - **Back-Channel Token Exchange & TLS Trust**: The Grafana container connects back-channel to Keycloak's token and userinfo endpoints via Nginx (`https://id.polaris.local`). TLS verification is strictly enforced using `./nginx/rootCA.pem` mounted to `/etc/grafana/certs/rootCA.pem` and configured via `GF_AUTH_GENERIC_OAUTH_TLS_CLIENT_CA`.
  - **Role-Based Access Control (RBAC)**: Maps Keycloak client roles (`admin`, `editor`, `viewer`) and realm roles to Grafana organization roles (`Admin`, `Editor`, `Viewer`) via JMESPath expression. Users with the `admin` role can be granted Grafana server admin access (`GF_AUTH_GENERIC_OAUTH_ALLOW_ASSIGN_GRAFANA_ADMIN=true`).
  - **Dual Authentication**: Grafana retains standard username/password login (`admin`/`admin`) alongside Keycloak SSO, ensuring automated tooling (`gcx-cli`) and emergency access continue seamlessly without disruption.
- **Grafana CLI (GCX)**: A pre-authenticated command-line container for interacting with Grafana:
  ```bash
  # Check CLI help
  ./gcx.sh --help

  # List dashboards
  ./gcx.sh dashboards list

  # Open interactive shell in the CLI container
  make gcx
  ```

---

## Infrastructure Catalog

| Service | Container Name | Image / Source | Host Port / URL | Credentials / Auth | Purpose |
|---|---|---|---|---|---|
| **otel-collector** | `otel-collector` | `otel/opentelemetry-collector-contrib` | `4317` (gRPC), `4318` (HTTP), `8889` (metrics) | - | Central telemetry ingestion and routing |
| **prometheus** | `prometheus` | `prom/prometheus:latest` | `http://localhost:9090` | - | Time-series metrics storage and engine |
| **tempo** | `tempo` | `grafana/tempo:latest` | `http://localhost:3200`, `4319` | - | Distributed tracing backend |
| **loki** | `loki` | `grafana/loki:3.1.0` | `http://localhost:3100` | - | High-efficiency log aggregation |
| **grafana** | `grafana` | `grafana/grafana:latest` | `http://localhost:3000` | Keycloak SSO (OIDC) / `admin:admin` | Unified visualization dashboard |
| **logseeding** | `logseeding` | `python:3.11-slim` | Internal only | - | Synthetic log stream generator |
| **gcx-cli** | `gcx-cli` | `debian:bookworm-slim` | CLI (`make gcx` / `./gcx.sh`) | Pre-authenticated Basic Auth | Grafana automation CLI |
