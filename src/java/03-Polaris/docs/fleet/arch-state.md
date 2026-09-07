# Architecture State

- **Active Domains:**
  - `Catalog`: Product catalog exploration and availability search (`/api/v1/products`, JPA Specification, H2 DB).
  - `Order`: Order placement, status tracking, cancellation state machine (`/api/v1/orders`, H2 DB).
  - `Gateway`: MCP JSON-RPC service (`mcp/polaris_mcp`), Keycloak OAuth2/OIDC integration.

- **Active Contracts:**
  - `Catalog Context`: v1 (OpenAPI spec in `OpenApiConfig.java` / `ProductController.java`)
  - `Order Context`: v1 (REST endpoints in `OrderController.java`)
  - `MCP Gateway Context`: v1 (Tools in `mcp/polaris_mcp/tools/`)

- **Open Slice Work Orders:**
  - *None currently open. Ready for dispatch.*
