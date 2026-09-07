# Architecture, Design & Code Principles

## Principle 1: Simplicity via Standardization & Lower-Layer Alignment

> **Core Tenet:** For every problem, look beneath surface-level symptoms and align directly with standard, established lower-layer protocols, specifications, and patterns. Prefer well-adopted industry standards over bespoke, proprietary, or overly complex abstractions.

### 1. REST APIs & HTTP Semantics
- **Strict Protocol Conformance (RFC 9110):** Keep REST APIs strictly aligned with standard HTTP specifications and semantics.
  - **HTTP Verbs:** Use methods according to their intended semantics:
    - `GET`: Safe, idempotent data retrieval.
    - `POST`: Non-idempotent resource creation or action execution.
    - `PUT`: Idempotent replacement of a target resource.
    - `PATCH`: Partial modification of a resource.
    - `DELETE`: Idempotent removal of a resource.
  - **Status Codes:** Use standard HTTP response status codes (`200 OK`, `201 Created`, `204 No Content`, `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `409 Conflict`, `422 Unprocessable Entity`, `500 Internal Server Error`).
    - *Anti-Pattern to avoid:* Do not return `200 OK` with an error message in the payload (e.g., `{"code": 200, "status": "fail"}`).
  - **Headers:** Leverage standard HTTP headers for cache control (`Cache-Control`, `ETag`), authentication (`Authorization`), content negotiation (`Content-Type`, `Accept`), location (`Location`), and idempotency (`Idempotency-Key`).
  - **Standard Error Payloads:** Follow RFC 7807 (Problem Details for HTTP APIs) for standardized error responses.

### 2. Architecture & Security Standards
- **Industry-Standard Security:** Adhere strictly to industry standards for authentication, authorization, and federation (e.g., OAuth 2.0, OpenID Connect, PKCE, standard JWT/JWKS verification). Never implement custom or proprietary authentication/crypto schemes.
- **Contract & Event Standards:** Align with established specifications (e.g., OpenAPI 3.x for API schemas, CloudEvents for messaging, OpenTelemetry for observability).
- **Configuration as Data (12-Factor):** All config (endpoints, credentials, feature flags) sourced from environment/config service, never hardcoded or baked into images. Dev-only credentials must be explicitly labeled as non-representative of production secret management.
- **Resilience Patterns:** Use standard, named patterns for cross-service calls — timeouts, retries with backoff, circuit breakers — rather than bespoke error-swallowing or infinite retry loops.

### 3. Business & Operational Alignment
- **Business Domain Alignment:** Align service and module boundaries with real-world business capabilities and operational domains (Domain-Driven Design bounded contexts).
- **"Walkable" Architecture:** The system architecture must be clear, transparent, and easy to trace:
  - Engineers and operators should be able to walk through an end-to-end request across services without hitting unexplained indirection, unnecessary proxy layers, or excessive abstraction.
  - Keep service boundaries matching operational ownership and deployment topology.
- **Traceable Integration Points:** Every capability claimed in documentation (e.g., "exposes MCP endpoints") must have a corresponding, visible component in the architecture diagram and service catalog. No capability should be asserted without a walkable path to it.
- **Testing Alignment:** Contract/integration tests should exercise the same standard interfaces (REST, OIDC, OTLP) as production — no test-only shortcuts that bypass the standards being enforced.

### 4. Data & Schema Standards
- **Schema Migrations:** Version-controlled, forward-only migrations (Flyway/Liquibase) — no ad-hoc DDL, no environment-specific schema drift. Local dev DB choice (e.g., H2) must not diverge from prod DB semantics in ways that hide real issues.
- **API Versioning:** Explicit, additive-first versioning (e.g., URI or header-based). Breaking changes require a new version and a documented deprecation window — never silent contract changes.

---

## Principle 2: Change Scope — Minimal, Bounded, and Vertically Complete

> **Core Tenet:** Every change should be as small as possible — but never smaller than one full vertical slice through a single bounded context (see §1.3). Minimality is the goal; a complete, verifiable slice within one context is the floor, and another context's boundary is the ceiling.

### 1. Lower Bound — Vertical Completeness
- A change must touch every layer required to make its feature slice actually work end-to-end within its bounded context (e.g. controller → application/service → repository → adapter → database).
  - *Anti-Pattern to avoid:* A change that stops partway — e.g. a controller change with no wired-up service/repository behind it — leaving the slice unverifiable and the feature non-functional on merge.
- If a full vertical slice is still too large for one change, split along an **internal seam** (e.g. controller↔application, or application↔infrastructure), with the seam defined as an explicit, contract-tested interface so each half is independently implementable and verifiable. This mirrors the Testing Alignment standard in §1.3 — the seam contract should be exercised the same way in tests as it would be in production.

### 2. Upper Bound — Context Containment
- A change must not reach into the domain logic, repository, or database of a bounded context other than the one it targets, regardless of how convenient direct access would be.
  - *Anti-Pattern to avoid:* Reaching across a context boundary to read/write another context's tables or internal services directly, rather than going through its published contract. This is the same violation as bypassing standard interfaces described in §1.3's Traceable Integration Points — it creates an unwalkable, undocumented path through the architecture.
- If a feature genuinely requires multiple bounded contexts, split the change **per context**, connected through an explicit, published contract (API call or event — see CloudEvents/OpenAPI standards in §1.2) rather than direct internal access. Each per-context change should itself satisfy the vertical-completeness bound above.

### 3. Sizing Discipline
- Between the two bounds above, always prefer the smaller change: don't bundle unrelated features, don't add speculative layers "while you're in there," and don't widen a change's scope just because a neighboring context would also benefit.
- If implementing a change requires editing another context's internals directly, treat that as a signal — either scope the change down, or the missing piece is an integration contract, not a wider diff.
- Self-check before finalizing a change:
  1. Does this change work end-to-end on its own, or does it leave a layer stubbed/unwired?
  2. Does this change modify domain logic, repositories, or tables belonging to more than one bounded context?
  3. If yes to (2), is the cross-context interaction happening through a published contract, with each side changed separately?

---

## Principle 3: Cross-Cutting Engineering Discipline — Observability, Quality & Anti-Monolith Modularity

> **Core Tenet:** Cross-cutting concerns are foundational architectural requirements, not optional afterthought add-ons. Every component must be observable by default, verified through automated tests, and decomposed into single-responsibility units — zero tolerance for "god classes" or "god files".

### 1. Observability by Default (The Three Pillars + Context)
- **Unified Distributed Tracing:**
  - Every external entrypoint (HTTP request, message queue consumer, RPC/MCP tool call, scheduled job) must establish or continue a distributed trace.
  - Always propagate standard W3C Trace Context headers (`traceparent`, `tracestate`) across network and process boundaries.
  - Record execution status, semantic attributes, and exceptions on the active span.
- **Correlated Structured Logging:**
  - Never emit unstructured, unformatted string logs in production or tooling layers.
  - All logs must be structured (machine-readable JSON or key-value) and automatically inject the active `trace_id` and `span_id`.
  - Isolate log streams from protocol transports (e.g., logging to `stderr` or OTLP, never polluting protocol `stdout`).
- **Telemetry-Driven Metrics:**
  - Standardize on core operational metrics for all components: invocation counters (labeled by status/outcome), latency histograms, and cross-boundary error rates.
  - Export metrics via standard protocols (e.g., OpenTelemetry OTLP / Prometheus scrapers) using non-blocking, asynchronous delivery to ensure telemetry never impedes application responsiveness.

### 2. Test-Driven Verification by Default (TDD & Fast Feedback)
- **Automated Verification Floor:** No feature, refactor, or bug fix is complete without automated tests proving its correctness.
- **Seam & Contract Testing:** When slicing vertical features or decoupling modules, write tests against explicit interface seams. Mock only at boundary interfaces; verify business logic with fast, deterministic unit and contract tests.
- **Cross-Cutting Verification:** Test suites must exercise not only the "happy path", but also error paths, resilience timeouts, edge conditions, and telemetry propagation (e.g., ensuring trace headers are properly formed and error spans recorded).

### 3. Anti-God Class & Anti-God File Architecture
- **Single Responsibility Discipline:**
  - No single class, module, or file may aggregate multiple distinct responsibilities (e.g., combining configuration, authentication handshakes, local HTTP servers, API clients, protocol parsing, and business logic into one file).
  - Deconstruct monoliths into explicit single-purpose modules: `config`, `auth`, `client`, `domain/tools`, `server/transport`, `telemetry`, and `cli`.
- **Walkable Seams & Dependency Direction:**
  - Dependencies must flow in one direction (high-level orchestration depends on domain/client abstractions; infrastructure adapters depend on interfaces). Avoid cyclical imports and circular service references.
- **Preserve Contracts via Facades:**
  - Refactoring an oversized class or file must not force callers to change their integration points immediately. Use the Facade pattern at the original entrypoint to maintain backward compatibility while cleanly delegating to the modular subsystem.