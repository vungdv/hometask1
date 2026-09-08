# Architecture State

- **Active Domains:**
  - `Catalog`: Product catalog exploration and availability search (`/api/v1/products`, JPA Specification, H2 DB).
  - `Order`: Order placement, status tracking, cancellation state machine (`/api/v1/orders`, H2 DB).
  - `Gateway`: MCP JSON-RPC service (`mcp/polaris_mcp`), Keycloak OAuth2/OIDC integration.

- **Active Contracts:**
  - `Catalog Context`: v1 (OpenAPI spec in `OpenApiConfig.java` / `ProductController.java`)
  - `Order Context`: v1 (OpenAPI spec in `OrderController.java`, DTO contracts in `vn.danang.polaris.dto`)
  - `MCP Gateway Context`: v1 (Tools in `mcp/polaris_mcp/tools/`)
  - `Observability SSO Contract`: OIDC Relying Party integration (`grafana` client in `keycloak/realm-export.json`, Generic OAuth in `docker-compose.override.yml`)

- **Open Slice Work Orders:**
  - [WO-001] Order Context API Contract Hardening & Test Suite -> Assigned: domain-dev-agent | Status: Verified
  - [WO-002] External API Performance & Contract Test Suite (k6) -> Assigned: domain-dev-agent | Status: Verified
  