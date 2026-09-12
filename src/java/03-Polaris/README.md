# Polaris - Enterprise Assistant Backend

Polaris is an enterprise backend service powering intelligent e-commerce operations, product discovery, conversational shopping, and order lifecycle management. Designed for direct integration with AI assistants and modern web clients, Polaris is organized as a modular polyglot monorepo exposing standards-compliant REST APIs secured by OAuth2/OIDC alongside a native Model Context Protocol (MCP) server.

---

## Business Domains & Capabilities

Polaris is organized around clear bounded contexts adhering to Domain-Driven Design (DDD) principles:

### 1. Product Catalog and Order management 
- (`/api/v1/products`): manage product & inventory
- (`/api/v1/categories`): manage product category
- (`/api/v1/order`): manage orders

### 2. AI Assistant Context (`/api/v1/assistant/chat`)
* **Autonomous Microservice**: Standalone service (`apps/polaris-assistant`) decoupled from core commerce databases, routed via gateway sub-path `/api/v1/assistant/*` under `https://polaris.local`.
* **MCP Integration**: Consumes product catalog and order operations from Polaris Core exclusively via Model Context Protocol (`/mcp/sse`).
* **Multi-Tool Hub**: Aggregates tools across Polaris Core and external MCP servers via `ExternalMcpHub`.
* **Conversational Commerce**: AI chat endpoint (`POST /api/v1/assistant/chat`) powered by foundation model integration (Google Gemini).

### 3. Model Context Protocol (MCP) Gateway Context (`/mcp/sse`, `/mcp/message`)
* **In-Process MCP Server**: Native Spring Boot MCP SDK integration in `apps/polaris` exposing standard JSON-RPC tools (`search_available_products`, `get_product_by_sku`, order query tools) for AI assistants.
* **Enterprise Security**: OAuth2/OIDC Bearer token authentication strictly enforced across all MCP endpoints (zero security bypass).

### 4. Identity & Access Context (`https://id.polaris.local`)
* **Standards-Based Authentication**: OAuth 2.0 and OpenID Connect (OIDC) via Keycloak (`polaris` realm).
* **Cryptographic Token Verification**: Stateless JWT validation with PKCE support for client browsers, Swagger UI, and autonomous assistants.

---

## Development Architecture

```mermaid
flowchart LR
    subgraph Observability["Observability/Grafana-Stack"]
        direction LR
        Grafana["Grafana"]
        Tempo["Tempo"]
        Loki["Loki"]
        Prometheus["Prometheus"]
    end
    subgraph Main[" "]
        direction TB
        subgraph L1["Clients"]
            direction TB
            k6["API Performance Tests"]
            SwaggerUI["Swagger UI / REST<br/>(/swagger-ui)"]
        end
        subgraph L2["Gateway"]
            Nginx["Nginx/Gateway<br/>(polaris.local :443)"]
        end
        subgraph L3["Apps"]
            direction TB
            Keycloak["Keycloak IdP<br/>(id.polaris.local)"]
            Polaris-App["Order, Product Catalog<br/>(polaris.local/*)"]
            Polaris-Assistant["AI Assistant<br/>(polaris.local/api/v1/assistant/*)"]
        end
    end
    L1 -->|HTTPS / REST| L2
    L2 -->|Proxy| L3
    Polaris-Assistant -->|MCP /sse| Polaris-App
    Main -.->|OTLP Telemetry| Observability
```

---

## Up the Stack in a Minute

Get the entire environment—including local HTTPS, identity provider, core backend, PostgreSQL databases, and telemetry—running locally in under 60 seconds:

### 1. Prerequisites
- Docker & Docker Compose
- macOS or Linux
- [`mkcert`](https://github.com/FiloSottile/mkcert) (for trusted local TLS certificates)

### 2. Quick Start
```bash
# 1. Initialize environment file
cp .env.template .env

# 2. Configure local TLS certificates and hostnames (one-time setup)
./scripts/setup-local-https-mac-m1.sh

# 3. Boot the complete local stack
make up
```

### 3. Access & Verify Endpoints

| Portal | URL | Credentials / Action |
|---|---|---|
| **Polaris Swagger UI** | [https://polaris.local/swagger-ui/index.html](https://polaris.local/swagger-ui/index.html) | Unified Swagger UI for Core & Assistant (select definition in top dropdown) &rarr; Authorize with `testuser` / `testpass` |
| **Assistant Chat API** | `POST https://polaris.local/api/v1/assistant/chat` | AI Assistant chat conversation endpoint (Requires OAuth2 Bearer token) |
| **Polaris MCP Endpoint** | [https://polaris.local/mcp/sse](https://polaris.local/mcp/sse) | MCP JSON-RPC SSE endpoint (Requires OAuth2 Bearer token) |
| **Keycloak Admin** | [https://id.polaris.local](https://id.polaris.local) | Username: `admin` \| Password: `admin` |
| **Grafana Telemetry** | [https://grafana.polaris.local](https://grafana.polaris.local) | Username: `admin` \| Password: `admin` (or Keycloak SSO) |
| **Polaris Database** | Internal `polaris-db:5432` | `make polaris-sql` opens psql into PostgreSQL 16 database |

---

## Essential Developer Commands

| Target | Command | Purpose |
|---|---|---|
| **Start Stack** | `make up` | Starts all services in the background (Apps + DBs + LGTM stack) |
| **Check Stack Status**| `make status` | Inspects container health, ports, and lifecycle states |
| **Stop Stack** | `make down` | Stops containers, networks, and persistent dev volumes |
| **Clean Stack** | `make clean` | Stops containers, removes orphan containers and volumes |
| **Rebuild Images** | `make build` | Rebuilds the multi-module Polaris Spring Boot application image |
| **Restart Service** | `make restart-<service>` | Restarts a single container (e.g. `make restart-polaris`) |
| **Run All Tests** | `make test` / `mvn clean test` | Executes local Java unit & domain integration tests across all modules |
| **Test Single Module** | `mvn test -pl apps/polaris` | Executes tests for a single module (e.g. `apps/polaris`) |
| **Playwright UI Testing** | `make playwright-ui` | Opens Swagger UI in Playwright for browser automation |
| **Close Playwright** | `make playwright-close` | Closes all open Playwright browser sessions |
| **Access Polaris DB** | `make polaris-sql` | Opens psql shell into the containerized PostgreSQL DB |

---

## Detailed Guides & Deep Dives

To keep daily development focused, detailed guides for specialized areas are maintained separately:

- 🔐 [**Local HTTPS Setup**](scripts/setup-local-https-mac-m1.sh): Manual setup script for `mkcert` and Java truststore.
- 📐 [**Architecture Decision Records (ADRs)**](docs/adr/): Formal architecture records (e.g., [ADR-0008: Polaris Assistant Isolation](docs/adr/0008-polaris-assistant-independent-application-mcp-architecture.md)).
- 📋 [**Product Requirements (PRDs)**](docs/prds/): Product requirement documents for catalog, orders, and AI assistant.
- 🏛️ [**Architecture, Design & Code Principles**](AGENTS.md): Foundational requirements for lower-layer protocol alignment, bounded context containment, and cross-cutting observability.
- [**Engineer-Guidelines**](Engineer-Guidelines.md)