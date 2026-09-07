# Init the idea
Pair with me to build a fleet, the goal is separate the role (agent) to solve different abstraction level of the problem. an architecture agent to maintain highlevel architecture, the contract between the
  sub-domain, sub-problems; a developer take a small enough scope and a contract for that scope and implement it. That is my idea, look into this project suggest me 2-3 decomposition options.

# Result
Here are 3 concrete decomposition options tailored to this project:
  ──────
  ### Option 1: Domain-Bounded Vertical Slice Fleet (DDD Context Model)

  Decomposes along business problem boundaries (Product Catalog vs Order Management).

  In this model, agents are partitioned by Bounded Context. The Architecture Agent acts as domain custodian, and Developer Agents build vertically complete slices end-to-end within a single context.

    ┌───────────────────────────────┐
    │                               │
    │      User([User Request])     │
    │                               │
    └───────────────┬───────────────┘
                    │
                    ▼
    ┌────────────────────────────────────────────────────────────┐
    │                                                            │
    │                     Architecture Agent                     │
    │                                                            │
    └──────────────────────────────┬─────────────────────────────┘
                                   │                                                  │
                                   │                                                  │
  ┌─────────────────────Publishes─Contract─Pack────────────────────┐                Pass
  │                     Strategic Governance                       │                  │
  │                                │                               │                  │
  │                                ▼                               │                  ▼
  │ ┌────────────────────────────────────────────────────────────┐ │ ┌─────────────────────────────────┐
  │ │                                                            │ │ │                                 │
  │ │                       Contract Pack                        │ │ │                                 │
  │ │                                                            │ │ │                                 │
  │ │                     • OpenAPI 3.x Spec                     │ │ │                                 │
  │ │                                                            ├─┤ │         Merge to Polaris        │
  │ │                    • Flyway DDL Schema                     │ │ │                                 │
  │ │                                                            │ │ │                                 │
  │ │                • Acceptance Tests (Gherkin)                │ │ │                                 │
  │ │                                                            │ │ │                                 │
  │ └──────────────────────────────┬─────────────────────────────┘ │ └─────────────────────────────────┘
  │                                │                               │
  └────────────────────────────────▼───────────────────────────────┴──────────────────▼
    ┌────────────────────────────────────────────────────────────┐   ┌─────────────────────────────────┐
    │                                                            │   │                                 │
    │               Developer Agent: Order Domain                │   │ Developer Agent: Catalog Domain ├──────────────────────────────┐
    │                                                            │   │                                 │                              │
    └──────────────────────────────┬─────────────────────────────┘   └────────────────┬────────────────┘                              │
                                   │                                                  │                                               │
                                   │                                                  │                                               │
  ┌────────────────────────────────┼──────────────────────────────────────────────────┼───────────────────────────────────────────────┼────────────────────────────┐
  │                                │                            Tactical Execution (Vertical Slices)                                  │                            │
  │                                │                                                  │                                               │                            │
  │                                ▼                                                  ▼                                               ▼                            │
  │ ┌────────────────────────────────────────────────────────────┐   ┌─────────────────────────────────┐   ┌─────────────────────────────────────────────────────┐ │
  │ │                                                            │   │                                 │   │                                                     │ │
  │ │ Java: OrderController -> OrderService -> Repo -> Flyway V4 │   │          CatSlice["Java         │   │ Python: ProductController -> JPA Spec -> MCP Tool"] │ │
  │ │                                                            │   │                                 │   │                                                     │ │
  │ └──────────────────────────────┬─────────────────────────────┘   └─────────────────────────────────┘   └─────────────────────────────────────────────────────┘ │
  │                                │                                                                                                                               │
  └────────────────────────────────▼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
    ┌────────────────────────────────────────────────────────────┐
    │                                                            │
    │                 Review{Contract Verifier}                  │
    │                                                            │
    └────────────────────────────────────────────────────────────┘

  • Architecture Agent Responsibilities:
      • Maintains domain boundaries and models interactions between contexts (e.g., ensuring Order references Product via immutable ID/SKU snapshot rather than leaky entity relationships).
      • Authors the Contract Pack: OpenAPI 3.x schema, DB migration DDL draft, RFC 7807 error catalog, and test acceptance criteria.
      • Verifies that cross-context communication uses only published contracts (AGENTS.md).
  • Developer Agent Responsibilities:
      • Takes one Bounded Context slice (e.g., "Implement Order Placement API & Inventory Reservation").
      • Implements the entire vertical stack: DB migration -> JPA Entity -> Service -> Controller -> Integration Tests (AGENTS.md).
      • Strictly forbidden from modifying files in other package trees.
  • Contract Boundary Artifact: A versioned contracts/<context>-spec.yaml containing OpenAPI endpoints, Flyway DDL, and Gherkin acceptance scenarios.
  • Best Suited For: Delivering complete end-to-end features (e.g., adding a shopping cart or shipment tracking) with high business cohesion.
  ──────
  ### Option 2: Polyglot Tier & Gateway Decomposition (Core Engine vs Protocol Gateway)

  Decomposes along runtime paradigms and technology stacks (Java Backend vs Python MCP).
  Polaris has an inherent split: enterprise Spring Boot microservice infrastructure vs AI-native Model Context Protocol (MCP) server integration.

    ┌───────────────────────────────────────────────────┐                                 ┌──────────────────────────────────────────────────────┐
    │                                                   │                                 │                                                      │
    │                 Architecture Agent                ├────────────────┐                │       Tools -.->|HTTP / OIDC Token| Endpoints        │
    │                                                   │                │                │                                                      │
    └─────────────────────────┬─────────────────────────┘ 2. MCP Tool Schema & OAuth SLA  └──────────────────────────────────────────────────────┘
                  1. REST Contract & Schema                              │
                              ▼                                          └────────────────────────────────────────────▼
    ┌───────────────────────────────────────────────────┐                                 ┌──────────────────────────────────────────────────────┐
    │                                                   │                                 │                                                      │
    │         Developer Agent: Spring Boot Core         │                                 │           Developer Agent: MCP AI Gateway            │
    │                                                   │                                 │                                                      │
    └─────────────────────────┬─────────────────────────┘                                 └───────────────────────────┬──────────────────────────┘
                              │                                                                                       │
                              │                                                                                       │
  ┌───────────────────────────┼───────────────────────────┐                             ┌─────────────────────────────┼────────────────────────────┐
  │                Java 21 / Spring Boot 3                │                             │                  Python 3.11│/ FastMCP                   │
  │                           │                           │                             │                             │                            │
  │                           ▼                           │                             │                             ▼                            │
  │ ┌───────────────────────────────────────────────────┐ │                             │ ┌──────────────────────────────────────────────────────┐ │
  │ │                                                   │ │                             │ │                                                      │ │
  │ │ Controllers, Services, DB Migrations, OTel Filter │ │                             │ │ Tool Handlers, PKCE Token Refresh, polaris_client.py │ │
  │ │                                                   │ │                             │ │                                                      │ │
  │ └───────────────────────────────────────────────────┘ │                             │ └──────────────────────────────────────────────────────┘ │
  │                                                       │                             │                                                          │
  └───────────────────────────────────────────────────────┘                             └──────────────────────────────────────────────────────────┘

  • Architecture Agent Responsibilities:
      • Governs the boundary between Spring Boot REST APIs and the Python MCP tool interface.
      • Formulates two synchronized contracts:
          1. Backend REST Contract: OpenAPI 3.x spec for Polaris endpoints with security scopes and HTTP status codes.
          2. AI Tool Contract: MCP tool JSON schemas (inputs/outputs), tool docstrings (for LLM reasoning), and token propagation rules.
      • Ensures telemetry parity: W3C traceparent injection from Python MCP into Java HTTP requests.
  • Developer Agent A (polaris-backend-dev):
      • Focus: Java Spring Boot ecosystem (src/main/java, pom.xml, Flyway).
      • Builds transactional endpoints, persistence, and Spring MockMvc tests matching the REST contract.
  • Developer Agent B (polaris-mcp-dev):
      • Focus: Python MCP client ecosystem (mcp/polaris_mcp/).
      • Implements product_search.py, polaris_client.py, PKCE authentication caching, and Python test mocks.
  • Contract Boundary Artifact: OpenAPI JSON specification + MCP Tool Definition files.
  • Best Suited For: Polaris's current stage, where features need to be exposed to AI assistants without mixing Python MCP mechanics and Java enterprise services.
  ──────
  ### Option 3: Contract-First Micro-Seam Pipeline (Specification-Driven Loop)

  Decomposes work into sequential, test-gated technical seams (Data Seam -> Service Seam -> Surface Seam).

  Rather than vertical or tech-stack splitting, this model treats the Architecture Agent as an active orchestrator that decomposes a feature into small, atomic micro-seams executed in locked sequence.

  ┌────────────────────┐   ┌─────────────────┐   ┌─────────────────────┐
  │ Architecture Agent │   │ Developer Agent │   │ Build / Test Runner │
  └──────────┬─────────┘   └────────┬────────┘   └──────────┬──────────┘
             │                      │                       │
             │ 1. Dispatch Seam 1 (Flyway DDL + Entity Contract) + Failing DDL Test
             ├─────────────────────►│                       │
             │                      │                       │
             │                      │ 2. Implements DB migration & JPA mapping
             │                      ├──────────────────────►│
             │                      │                       │
             │ 3. Seam 1 Verified (Tests Green)             │
             │◄┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┤
             │                      │                       │
             │ 4. Dispatch Seam 2 (Service Contract Interface) + Unit Contract Tests
             ├─────────────────────►│                       │
             │                      │                       │
             │                      │ 5. Implements Service & Business Validations
             │                      ├──────────────────────►│
             │                      │                       │
             │ 6. Seam 2 Verified (Tests Green)             │
             │◄┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┤
             │                      │                       │
             │ 7. Dispatch Seam 3 (REST Controller & RFC 7807 Error Contract)
             ├─────────────────────►│                       │
             │                      │                       │
             │                      │ 8. Implements Web Layer & MockMvc Tests
             │                      ├──────────────────────►│
             │                      │                       │
             │ 9. Full Slice Verified                       │
             │◄┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┤
             │                      │                       │

  • Architecture Agent Responsibilities:
      • Acts as a Contract & Test Authority.
      • Before any developer code is written, the Architecture Agent generates the Test Fixtures & Contract Tests first (e.g., a test asserting an order status transition fails if already shipped).
      • Feeds the Developer Agent one seam at a time, keeping context windows small and focused.
  • Developer Agent Responsibilities:
      • Pure execution engine: takes pre-written tests + interface definitions and writes minimal production code to satisfy the contract.
      • Restricted from inventing uncontracted methods or modifying files outside the active seam.
  • Contract Boundary Artifact: Executable contract tests (Java unit/mock tests, OpenAPI validators) that fail prior to implementation and pass upon completion.
  • Best Suited For: Complex, high-consequence business rules (e.g. order cancellation constraints, payment/refund invariants, race-condition prevention in inventory allocation).
  ──────
  ### Comparison Matrix

   Criteria                                           | Option 1: Domain-Bounded Slice                     | Option 2: Polyglot Tier / Gateway                 | Option 3: Micro-Seam Pipeline
  ----------------------------------------------------|----------------------------------------------------|---------------------------------------------------|---------------------------------------------------
   Separation Axis                                    | Business Domain (Catalog vs Orders)                | Technology / Layer (Spring vs MCP)                | Temporal / Seam (Schema → Service → API)
   Developer Scope                                    | Full vertical slice per domain                     | Language-specific layer                           | Single file / class seam
   Context Safety                                     | High (isolated directories)                        | Very High (isolated language runtimes)            | Maximum (isolated tests per step)
   Coordination Overhead                              | Medium (architecture sets domain specs)            | Low (stable wire protocol / OpenAPI)              | High (step-by-step review gate)
   Polaris Fit                                        | Ideal for adding new business domains              | Ideal for immediate next steps in Polaris         | Ideal for sensitive transactional logic

  ### Recommendation

  For Polaris in its current state, Option 2 (Polyglot Tier & Gateway) blended with Option 1 is the most effective approach:

  1. Architecture Agent: Defines the high-level domain contract (OpenAPI specs + MCP tool schemas) and guards AGENTS.md standards.
  2. Backend Developer Agent: Works exclusively in src/ to expand core capabilities (e.g., completing the OrderService.java and OrderController.java cancellation & placement flows).
  3. MCP Developer Agent: Works exclusively in mcp/ to expose these new order tools to AI assistants via polaris_mcp.

  Which decomposition model best matches how you want to run this fleet, or would you like to drill into defining the exact agent system prompts and contract formats for one of them?

────────────────────────────────────────────────────────────
# Decision (Human)
> Take option 1 as core, define me the agent system prompts from that. one of the success criteria is each agent has ability to maintain its own state, it should be very concise like a person can read it in 2 minutes, visualize in a properly way for the important and stable architecture only, every detail should live in code.

