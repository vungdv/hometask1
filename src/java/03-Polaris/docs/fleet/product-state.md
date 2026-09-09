# Polaris Product State & Roadmap

- **Active Milestone:** Q4 Milestone: Conversational Commerce & Web AI Assistant Core
- **Product Vision:** Build a cloud-native, AI-agent-ready e-commerce platform that pairs traditional commerce capabilities (Catalog, Order, Pricing) with native Model Context Protocol (MCP) integrations, conversational chat surfaces, and self-healing API feedback.

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

4. **Theme 4: Conversational Commerce & Multi-Surface Clients**
   - Interactive Web Chat AI Assistant for retail shoppers and internal store staff.
   - Grounded natural language catalog exploration, ambiguous item disambiguation, and conversational cart staging.
   - Mandatory Human-in-the-Loop confirmation gates for state-mutating actions.
   - Cross-device server-side session persistence and strict anti-IDOR authorization boundaries.

---

### 2. PRD Registry & Delivery Status

| PRD ID | Title | Target Context | Status | Target Milestone |
| :--- | :--- | :--- | :--- | :--- |
| **PRD-001** | Product Discovery & Stock Query MCP Tools | Catalog / Gateway | **Delivered** | Q3 Hardening |
| **PRD-002** | Order Lifecycle & Cancellation Domain Operations | Order | **Delivered** | Q3 Hardening |
| **PRD-003** | Enterprise Authentication & Identity Integration | Gateway / Security | **Delivered** | Q3 Hardening |
| **PRD-004** | Advanced Catalog Filtering & Self-Correcting Pagination | Catalog | **Delivered** | Q3 Hardening |
| **PRD-005** | Comprehensive Order Lifecycle Capabilities & AI Shop Agent Integration | Order / Gateway / MCP | **Delivered** | Q3 Hardening |
| **PRD-006** | Internal Staff & Shopper AI Chat Web Assistant | Web Client / Catalog / Order / Gateway | **Ready for Architecture** | Q4 Conversational Core |

---

### 3. Delivered Capabilities
- [x] **[PRD-001]** Product Discovery & Stock Query MCP Tools (Accepted)
- [x] **[PRD-002]** Order Lifecycle & Cancellation Domain Operations (Accepted)
- [x] **[PRD-003]** Enterprise Authentication & Identity Integration (Accepted)
- [x] **[PRD-004]** Advanced Catalog Filtering & Self-Correcting Pagination (Accepted)
- [x] **[PRD-005]** Comprehensive Order Lifecycle Capabilities & AI Shop Agent Integration (Accepted on 2026-09-09)

---

### 4. Active / Backlog Feature Candidates

- **[PRD-007 Candidate] Persistent Shopping Cart & Stock Reservation Engine:**
  - *Context:* Order / Catalog
  - *Objective:* Provide persistent multi-session shopping carts with temporary stock holds and cart abandonment detection.
- **[PRD-008 Candidate] Promotion & Discount Voucher Engine:**
  - *Context:* Pricing / Order
  - *Objective:* Apply targeted percentage and fixed discounts to orders with coupon code validation.
- **[PRD-009 Candidate] Webhook Notifications & External Event Streaming:**
  - *Context:* Gateway / Order
  - *Objective:* Stream CloudEvents for order lifecycle changes to external webhook consumers.
