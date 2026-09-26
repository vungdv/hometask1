# Slice Work Order: WO-021
## Title: Confirm / Cancel Order Draft Endpoints and TTL Expiration Enforcement

- **Target Modules:** `apps/polaris-assistant`; small additive helper in `libs/polaris-common` (shared kernel — see Task 2)
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0004](../decisions/0004-web-chat-ai-assistant-architecture.md) §1.B (mutation transport), §3.A/§3.B (state machines & TTL), §5 (sequence diagram, confirm steps 16–22)
- **Product Reference:** [PRD-003](../../business/prds/PRD-003-web-chat-ai-assistant.md) §3.5 / Scenario 5
- **Event Model Reference:** [EM-001](../event-models/EM-001-order-staging-out-of-stock-exception.md) §3 Gap Analysis row "Draft expiration handling"
- **Depends on:** WO-019 (entities/repositories), WO-020 (a draft must exist to confirm/cancel; reuses its `DraftItemSnapshot`/TTL config)
- **Bounded Context:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Cross-Context Contracts Consumed:** `POST /api/v1/orders` (Order context, published, stable — [`OrderController.java:50-89`](../../../apps/polaris/src/main/java/vn/danang/polaris/order/web/controller/OrderController.java)) and `GET /api/v1/products/sku/{sku}` (Catalog context, published, stable — re-verification at confirm time, reusing WO-020's `CatalogRestClient`). **No direct call to `OrderService` or any Order/Catalog repository.**
- **Status:** PROPOSED

---

## 1. Objective & Scope

ADR-0004 §1.B specifies two mutation endpoints that don't exist yet, and EM-001 §3 records `DraftExpiredException` as dead code — defined and handled in `GlobalExceptionHandler`, never thrown. This WO adds the confirm/cancel REST endpoints, makes `DraftExpiredException` live, and closes the TTL loop with both a lazy (at-confirm-time) and an active (scheduled sweep) expiration path.

**Note on what this WO is *not*:** the model never calls a "confirm" or "cancel" tool. Per ADR-0004 §1.B and §5, confirmation is an explicit human action (a button click) hitting a plain REST endpoint directly from the presentation client — it does not go through the ReAct tool-call loop at all. This keeps the slice's surface area to two `@RestController` methods plus the expiration sweep; there is no MCP/tool-schema change here (that's entirely WO-020's and WO-022's territory).

**Constraint Checklist:**
- [ ] Confirming calls `POST /api/v1/orders` — never `OrderService` directly, never a JPA repository belonging to the Order context.
- [ ] `DraftExpiredException` (already defined, already handled) is thrown, not reimplemented.
- [ ] Confirming an already-`CONFIRMED`/`CANCELLED`/`EXPIRED` draft, or a draft whose TTL has lapsed, never reaches `POST /api/v1/orders` — the state guard runs first.
- [ ] The scheduled TTL sweep only touches `assistant_order_drafts`; it must not (and structurally cannot, since it never calls Catalog/Order) affect any other context's data.
- [ ] 100% unit/integration test pass (`mvn clean test -pl apps/polaris-assistant`).

---

## 2. Detailed Technical Tasks

### Task 1: `OrderRestClient` — Typed Client for the Published Order Contract
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/OrderRestClient.java`

Mirrors WO-020's `CatalogRestClient` (same `RestClient` + `UserContext.resolveBearerToken()` pattern, same new-property convention — reuse `polaris.core.rest.base-url` from WO-020, do not introduce a second base-URL property for the same host):

```java
public interface OrderRestClient {
    OrderPlacedView placeOrder(Long customerId, List<OrderItemRequest> items, String idempotencyKey);
    // throws a typed exception carrying the downstream ProblemDetail on non-2xx, so the
    // Assistant's own exception handling can decide whether to proxy it as-is (Task 4)
}
public record OrderPlacedView(String orderNumber, String status, java.math.BigDecimal totalAmount) {}
```

`OrderItemRequest` here is the existing published DTO shape from `apps/polaris` (`sku`, `quantity`) — reference it by shape, not by importing the class across the app boundary; define an identical small record locally in the Assistant context if a shared DTO module isn't already on the classpath, rather than adding a compile-time dependency from `apps/polaris-assistant` onto `apps/polaris`'s own JAR (that dependency would itself be a Principle 2.2 violation — REST is the contract, not the DTO class file).

### Task 2: `DraftNotActionableException` (shared kernel, alongside `DraftExpiredException`)
**File (new):** `libs/polaris-common/src/main/java/vn/danang/polaris/web/exception/DraftNotActionableException.java`

`DraftExpiredException` already lives in `libs/polaris-common` and is already handled by the shared `GlobalExceptionHandler`, which `PolarisAssistantApp` already component-scans (`scanBasePackages` includes `vn.danang.polaris.web.exception`) — this is an established pattern for Assistant-specific problem types, not a new one. Following it:

```java
public class DraftNotActionableException extends RuntimeException {
    private final String draftId;
    private final String currentStatus; // the draft's AssistantOrderDraftStatus.name()
    public DraftNotActionableException(String draftId, String currentStatus) {
        super(String.format("Draft '%s' is not awaiting confirmation (current status: %s).", draftId, currentStatus));
        this.draftId = draftId; this.currentStatus = currentStatus;
    }
    // getters
}
```

Covers both confirm and cancel attempts against a draft that isn't `WAITING_CONFIRMATION` (there is only one actionable state, so one exception serves both endpoints).

### Task 3: New `@ExceptionHandler` in the Shared `GlobalExceptionHandler`
**File:** `libs/polaris-common/src/main/java/vn/danang/polaris/web/exception/GlobalExceptionHandler.java`

```java
@ExceptionHandler(DraftNotActionableException.class)
public ProblemDetail handleDraftNotActionableException(DraftNotActionableException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    problem.setTitle("Draft Not Actionable");
    problem.setType(URI.create("https://polaris.local/errors/draft-not-actionable"));
    problem.setProperty("draftId", ex.getDraftId());
    problem.setProperty("currentStatus", ex.getCurrentStatus());
    problem.setProperty("remedy", "Stage a new order draft; this one is no longer awaiting confirmation.");
    return problem;
}
```

This is an **additive** change to a shared file — no existing handler method's signature or output shape changes, so every existing consumer of `GlobalExceptionHandler` is unaffected. New RFC 7807 `type` value: `https://polaris.local/errors/draft-not-actionable` (409).

### Task 4: `AssistantOrderDraftResponse` DTO
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/dto/AssistantOrderDraftResponse.java`

```java
public record AssistantOrderDraftResponse(
    String draftId, String sessionId, String status,
    List<DraftItemSnapshot> items, BigDecimal totalAmount,
    Instant expiresAt, String confirmedOrderNumber
) {}
```

WO-020's SSE `draft` event payload is a projection of this same record (same field names, minus `sessionId`/`confirmedOrderNumber`) — defining it here and having WO-020 reference it (rather than each WO inventing its own draft-summary shape) is what keeps the wire format identical whether the draft summary arrives via SSE or via this endpoint's response body.

### Task 5: Confirm Endpoint
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/web/AssistantChatController.java` (new method) — or a new `DraftController` if the existing controller is judged to be taking on too many responsibilities; either is acceptable, but pick one and keep `AssistantChatController` from becoming a god-class per AGENTS.md 3.3.

```
POST /api/v1/assistant/sessions/{sessionId}/drafts/{draftId}/confirm
Headers: Authorization: Bearer <JWT>, Idempotency-Key: <UUIDv4>  (REQUIRED — 400 if absent; stricter
         than POST /api/v1/orders's own optional header, because this endpoint is the sole gateway
         a human clicks exactly once, and ADR-0004 §1.B lists the header without an "optional" qualifier)
```

Logic:
1. `draft = repository.findByIdAndSessionId(draftId, sessionId)` — 404 (`ResourceNotFoundException`, already shared) if absent.
2. If `draft.status != WAITING_CONFIRMATION` → throw `DraftNotActionableException(draftId, draft.status)` → 409.
3. If `Instant.now().isAfter(draft.expiresAt)` → set `draft.status = EXPIRED`, save, then throw `DraftExpiredException(draftId, "...")` → 409 `draft-expired` (the handler already exists; this is the first line of code in the whole codebase that actually throws it).
4. Re-verify stock for every `DraftItemSnapshot` via `CatalogRestClient.getBySku` (WO-020). If any item's snapshotted quantity now exceeds live stock, **do not** confirm and **do not** mutate the draft's status — return the same `problem` shape WO-020/WO-022 use (`InsufficientStockActions`), so the shopper can re-stage. This implements the mitigation ADR-0004 §"Negative Consequences" names for the "Eventual Consistency Window During Draft Review" trade-off.
5. Else, call `OrderRestClient.placeOrder(draft.customerId, itemsFromSnapshot, idempotencyKeyFromHeader)`.
   - **201** → `draft.status = CONFIRMED`, `draft.confirmedOrderNumber = response.orderNumber()`; `session.status = CONFIRMED`; return `201 Created`, `Location: /api/v1/orders/{orderNumber}`, body = the proxied `OrderResponse` JSON from the Order context (pass-through, not re-shaped).
   - **Any downstream 4xx** (e.g. stock exhausted between step 4 and this call) → proxy the downstream `ProblemDetail` as-is; draft remains `WAITING_CONFIRMATION`.

**Known Limitation (deliberately out of scope, flagged rather than silently worked around):** ADR-0004 §3.B promises "Price Guarantee: ... prices are guaranteed" within the TTL window, but the published `CreateOrderRequest`/`OrderItemRequest` contract (`apps/polaris/.../order/dto/`) has no field to carry a locked/expected unit price — `POST /api/v1/orders` always re-prices from the *live* catalog price at commit time. Honoring the price guarantee literally would require an **additive contract change to the Order context's published `POST /api/v1/orders` request shape** (e.g. an optional expected-price-per-line field, validated with a tolerance), which is a stable-boundary change requiring its own ADR and its own WO — not something to smuggle into this slice by having the Assistant context reach past the contract. This WO ships the staging/confirm *workflow* faithfully; the price-lock guarantee itself remains a named, tracked gap for a future ADR.

### Task 6: Cancel Endpoint

```
POST /api/v1/assistant/sessions/{sessionId}/drafts/{draftId}/cancel
Response 200: AssistantOrderDraftResponse with status="CANCELLED"
```

Same lookup/state-guard as confirm (Task 5, steps 1–2; an expired draft can still be cancelled — cancelling an already-expired draft is a no-op-ish tidy-up, not an error, so skip the TTL check here). On success: `draft.status = CANCELLED`; `session.status = CANCELLED` (per [ADR-0004 §3.A](../decisions/0004-web-chat-ai-assistant-architecture.md#a-assistant-session-state-machine) `WAITING_CONFIRMATION --> CANCELLED`). The later `CANCELLED --> ACTIVE` transition on the diagram happens automatically the next time the shopper sends a message — that's WO-020 Task 4 step 1's session-resume logic, not something this endpoint does itself.

### Task 7: Scheduled TTL Sweep
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/DraftExpirationScheduler.java`

```java
@Scheduled(fixedDelayString = "${polaris.assistant.draft.expiration-sweep-interval:PT60S}")
public void expireOverdueDrafts() {
    int expired = repository.updateStatusExpiredWhereWaitingAndPastTtl(Instant.now());
    if (expired > 0) log.info("Expired {} order draft(s) past TTL", expired);
}
```

Uses the bulk-update repository method WO-019 Task 6 defines (which in turn exploits the `idx_assistant_drafts_expiry` index from WO-019 Task 1) — this is proactive/eager expiration on top of Task 5's lazy check, so a draft that's simply abandoned (no further confirm attempt ever made) doesn't sit in `WAITING_CONFIRMATION` forever. Requires `@EnableScheduling` on `PolarisAssistantApp` if not already present.

---

## 3. Given/When/Then Acceptance Criteria

1. **Given** a `WAITING_CONFIRMATION` draft within its TTL and sufficient live stock, **when** `POST .../drafts/{draftId}/confirm` is called with a valid `Idempotency-Key`, **then** the response is `201 Created` with the `OrderResponse` body, the draft transitions to `CONFIRMED` with `confirmed_order_number` set, and the session transitions to `CONFIRMED`.
2. **Given** a draft whose `expires_at` is in the past, **when** confirm is called, **then** the response is `409 Conflict` with `type: https://polaris.local/errors/draft-expired`, the draft is (or already was) marked `EXPIRED`, and `POST /api/v1/orders` is never called.
3. **Given** an already-`CONFIRMED` draft, **when** confirm is called again with the same `Idempotency-Key` (double-click), **then** the response is `409 Conflict` with `type: https://polaris.local/errors/draft-not-actionable` — no duplicate order is created.
4. **Given** a `WAITING_CONFIRMATION` draft whose snapshotted item now exceeds live stock, **when** confirm is called, **then** the response carries the out-of-stock `problem` shape with `actions[]`, the draft remains `WAITING_CONFIRMATION`, and `POST /api/v1/orders` is never called.
5. **Given** a `WAITING_CONFIRMATION` draft, **when** `POST .../drafts/{draftId}/cancel` is called, **then** the response is `200 OK` with `status="CANCELLED"`, and a subsequent confirm attempt on the same `draftId` returns `409 draft-not-actionable`.
6. **Given** a `WAITING_CONFIRMATION` draft whose `expires_at` has passed and no one ever calls confirm, **when** the scheduled sweep next runs, **then** the draft transitions to `EXPIRED` without any HTTP request having touched it.

---

## 4. Verification & Acceptance Criteria

```bash
mvn clean test -pl apps/polaris-assistant
mvn clean test
```
Playwright/manual: stage a draft, wait past the TTL (or use a shortened test TTL profile), attempt confirm, verify `409 draft-expired` in the Network tab and that `apps/polaris`'s `orders` table gained no new row.
