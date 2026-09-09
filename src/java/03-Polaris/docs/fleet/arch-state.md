# Architecture State

- **Active Domains:**
  - `Catalog`: Product catalog exploration and availability search (`/api/v1/products`, JPA Specification, H2 DB).
  - `Order`: Order placement, status tracking, cancellation state machine (`/api/v1/orders`, H2 DB).
  - `Gateway`: MCP JSON-RPC service (`mcp/polaris_mcp`), Keycloak OAuth2/OIDC integration.

- **Active Contracts:**
  - `Catalog Context`: v1 (OpenAPI spec in `ProductController.java`, `CategoryController.java` — `/api/v1/products`, `/api/v1/categories`)
  - `Order Context`: v1 (OpenAPI spec in `OrderController.java`, DTO contracts in `vn.danang.polaris.dto`)
  - `MCP Gateway Context`: v2 Native In-Process (`McpSyncServer` at `/mcp/sse`, `/mcp/message`, `ProductMcpTools`, `OrderMcpTools`)
- **Architecture Decision Records:**
  - [ADR-0001: Architectural Alternatives for Exposing Polaris Services via Model Context Protocol (MCP)](../adr/0001-mcp-server-alternatives.md)
  - [ADR-0002: Architectural Strategies for Pagination & Sort Validation: Boundary Placement and Single Responsibility](../adr/0002-pagination-sort-validation-architecture.md)

- **Open Slice Work Orders:**
  - [WO-001] Order Context API Contract Hardening & Test Suite -> Assigned: domain-dev-agent | Status: Verified
  - [WO-002] External API Performance & Contract Test Suite (k6) -> Assigned: domain-dev-agent | Status: Verified
  - [WO-003] Grafana SSO Integration with Keycloak (OIDC/RBAC & TLS Trust) -> Assigned: domain-dev-agent | Status: Verified
  - [WO-004] Product Catalog Hierarchy & Taxonomy Domain Slice -> Assigned: domain-dev-agent | Status: Verified
  - [WO-005] Catalog Pagination & Sort Contract Hardening with Actionable RFC 7807 Error Feedback -> Assigned: domain-dev-agent | Status: Verified
  - [WO-006] Native Spring Boot MCP Server Architecture Implementation -> Assigned: domain-dev-agent | Status: Verified
  - [WO-007] Upgrade Java MCP SDK to v2.0.1 GA -> Assigned: domain-dev-agent | Status: Verified
  - [WO-008] Enforce OAuth2 Authentication on MCP Gateway Endpoints & Fix Security Bypass -> Assigned: domain-dev-agent | Status: Verified
  - [WO-009] Comprehensive Order Lifecycle API & AI Shop Agent MCP Suite -> Assigned: domain-dev-agent | Status: Verified


  