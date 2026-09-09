# Polaris Product Definition Guidelines

This document provides product definition guidelines, persona constraints, and acceptance scenario standards for authoring PRDs in Polaris.

---

### 1. Persona & Authorization Boundaries (Anti-IDOR by Design)

- **Retail Shoppers (`ROLE_USER`):** Must always be bound to their authenticated customer account extracted server-side from JWT claims (`sub` / `preferred_username`). Shoppers must **never** be permitted to supply or override `customer_id`. Any attempt to inspect, stage, or cancel orders for another customer must be defined as an RFC 7807 `403 Forbidden` rejection.
- **Store Staff / Operators (`ROLE_STAFF`, `ROLE_ADMIN`):** May act on behalf of customers by specifying `customer_id`. The PRD must mandate recording the authenticated staff operator identity (`operator_id`) in audit logs and order metadata.

---

### 2. Grounding, Disambiguation & Conversational Staging

- **Real-Time Data Grounding:** All product specifications, availability, and prices must be backed by live domain contracts; zero tolerance for LLM guesswork.
- **Ambiguous Product Disambiguation:** When natural language input matches multiple catalog items, the PRD must require the assistant to render interactive **Product Disambiguation Cards** allowing the user to select the exact SKU before staging.
- **Conversational Staging:** Enable multi-turn conversational drafting (adding items, adjusting quantities, removing items) with dynamic recalculation of line subtotals and grand totals before final commitment.

---

### 3. Mandatory Human-in-the-Loop (HITL) State Mutation Gates

- Under no circumstances may an assistant execute state-mutating actions (order placement, order cancellation, payment) autonomously without an explicit confirmation gate.
- The PRD must specify an interactive **Review Card** displaying: customer identification, itemized line items, unit prices, grand total, inventory impact notice, and an active confirmation button or unambiguous affirmative trigger.

---

### 4. Machine-Actionable Error Diagnostics (RFC 7807 UI Problem Cards)

- PRDs must not accept vague error messages. Every error flow (out of stock, invalid quantity, state conflict) must specify that RFC 7807 `ProblemDetail` responses be rendered as **Problem Cards** equipped with one-click remedy buttons (e.g. `[Adjust Quantity to Available Stock]`, `[Search Alternatives]`).

---

### 5. Server-Side Session Persistence & Draft Lifecycles

- State persistence must adhere to enterprise architecture standards (Topology B): sessions, conversation turns, and staged drafts must reside in the server-side enterprise database. Client runtimes only retain the active `session_id`.
- PRDs must define draft Time-To-Live (TTL, e.g. 15 minutes) and mandatory pre-commit re-verification for catalog prices and inventory stock to prevent race conditions.

---

### 6. Resiliency & Deterministic Fallback Modes

- Every conversational feature must define a graceful fallback mode (e.g. deterministic rule/keyword engine) that operates when external LLM provider credentials are not configured or external AI APIs experience downtime.

---

### 7. Mandatory Edge Case Scenarios in Acceptance Criteria

Every PRD must formulate explicit Given/When/Then scenarios covering:
1. Staged draft expiration and stale price/stock invalidation.
2. Concurrent stock depletion between staging and confirmation (race condition).
3. Auth token expiration during active chat and silent PKCE refresh.
4. IDOR cross-tenant access violation attempts (`403 Forbidden`).
5. Degradation to deterministic fallback mode.
