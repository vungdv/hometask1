# Technical Documentation

How Polaris is built: architecture, decisions, integration, and data.

- [`decisions/`](decisions/) — Architecture Decision Records (ADR-NNNN). One record per significant technical choice: context, options considered, decision, consequences.
- [`architecture/`](architecture/) — Sequence diagrams and component/orchestration design docs.
- [`event-models/`](event-models/) — Event Models (EM-NNN): Command/Event/Read-Model breakdowns of individual use cases and their exception paths, including where the as-built implementation diverges from the as-designed flow.
- [`work-orders/`](work-orders/) — Slice Work Orders (WO-NNN): vertically-complete implementation task specs, each tracing back to a [PRD](../business/prds/) and an [ADR](decisions/). Latest: [WO-019](work-orders/WO-019-assistant-session-and-draft-persistence.md)–[WO-022](work-orders/WO-022-structured-remedy-actions-across-mcp-boundary.md) implement the [EM-001](event-models/EM-001-order-staging-out-of-stock-exception.md) designed order-staging-draft flow per [ADR-0004](decisions/0004-web-chat-ai-assistant-architecture.md).
- [`data/`](data/) — Database schema and seed data.

## Traceability
Business need → technical decision → implementation:
`../business/prds/PRD-xxx.md` → `decisions/00xx-*.md` → `work-orders/WO-xxx-*.md`

A use case's branch/exception paths can additionally be traced command-by-command in `event-models/EM-xxx-*.md`.
