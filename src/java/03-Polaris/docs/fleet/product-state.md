# Polaris Product State & Roadmap

- **Active Milestone:** Q3 Production Hardening & AI Agent Tool Core
- **Product Vision:** Build a cloud-native, AI-agent-ready e-commerce platform that pairs traditional commerce capabilities (Catalog, Order, Pricing) with native Model Context Protocol (MCP) integrations and self-healing API feedback.

---

### 1. Strategic Themes

1. **Theme 1: Catalog & Discovery**
   - Rich product hierarchy and categorization.
   - Real-time stock-aware search and filtering.
   - AI discovery assistant tools via MCP (`search_available_products`, `get_product_by_sku`).

2. **Theme 2: Checkout & Order Lifecycle**
   - Order placement, customer tracking, and state transitions (`PLACED` &rarr; `CONFIRMED` &rarr; `SHIPPED`).
   - Self-service and AI-assisted order cancellation with strict business conflict guards (`409 Conflict`).

3. **Theme 3: Autonomous AI Agent & Developer Experience**
   - Self-healing RFC 7807 error feedback with explicit remedies and allowed values for LLM tool self-correction.
   - Enterprise-grade OAuth2/OIDC security across both REST and MCP JSON-RPC gateways.
   - End-to-end distributed tracing observability (W3C `traceparent`).

---

### 2. PRD Registry & Delivery Status

| PRD ID | Title | Target Context | Status | Target Milestone |
| :--- | :--- | :--- | :--- | :--- |
| **PRD-001** | Product Discovery & Stock Query MCP Tools | Catalog / Gateway | **Delivered** | Q3 Hardening |
| **PRD-002** | Order Lifecycle & Cancellation Domain Operations | Order | **Delivered** | Q3 Hardening |
| **PRD-003** | Enterprise Authentication & Identity Integration | Gateway / Security | **Delivered** | Q3 Hardening |
| **PRD-004** | Advanced Catalog Filtering & Self-Correcting Pagination | Catalog | **Delivered** | Q3 Hardening |

---

### 3. Active / Backlog Feature Candidates

- **[PRD-005 Candidate] Shopping Cart & Multi-Item Checkout Flow:**
  - *Context:* Order / Catalog
  - *Objective:* Enable shoppers and AI agents to stage items in an active cart, reserve stock temporarily, and execute multi-item checkout.
- **[PRD-006 Candidate] Promotion & Discount Voucher Engine:**
  - *Context:* Pricing / Order
  - *Objective:* Apply targeted percentage and fixed discounts to orders with coupon code validation.
