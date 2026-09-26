# Slice Work Order: WO-020
## Title: StageOrderDraft Orchestration, Read-Only Stock Verification, and SSE Streaming Transport

- **Target Modules:** `apps/polaris-assistant`; small additive helper in `libs/polaris-common` (shared kernel — see Task 2)
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0004](../decisions/0004-web-chat-ai-assistant-architecture.md) §1.A (SSE wire protocol), §3.B (TTL), §4.A (tool schema), §5 (sequence diagram)
- **Product Reference:** [PRD-003](../../business/prds/PRD-003-web-chat-ai-assistant.md) §3.5 / Scenario 5, FR-5
- **Event Model Reference:** [EM-001](../event-models/EM-001-order-staging-out-of-stock-exception.md) §1 (as-designed flow, frames 01–09)
- **Depends on:** WO-019 (`AssistantSession`/`AssistantOrderDraft` entities & repositories). **Depended on by:** WO-022, which reuses the `InsufficientStockActions` helper (Task 2) and the `ToolResult.actions` field (Task 5) this WO creates — build order is fixed at WO-019 → WO-020 → WO-021 → WO-022, matching the WO numbers; WO-022 does not create either artifact itself.
- **Bounded Context:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Cross-Context Contracts Consumed:** `GET /api/v1/products/sku/{sku}` (Catalog context, published, stable — [`ProductController.java:87-94`](../../../apps/polaris/src/main/java/vn/danang/polaris/catalog/web/controller/ProductController.java)). **No other context is touched.** In particular, this WO never calls `OrderService` or `POST /api/v1/orders` — staging is read-only by design (ADR-0004 §1.A: "Streaming Idempotency: Streaming endpoints NEVER mutate persistent domain state").
- **Status:** PROPOSED

---

## 1. Objective & Scope

EM-001 §2 records that "staging" today is just the LLM's own reasoning — there is no `StageOrderDraft` command, no `draftId`, nothing persisted, and stock is verified and deducted atomically inside `OrderService.placeOrder` with no separate read-only check. This WO builds the missing command: a new local tool `stage_order_draft` that verifies stock read-only against Catalog, upserts an `AssistantOrderDraft` (WO-019), and streams the outcome back over the SSE transport ADR-0004 §1.A specifies (`draft` / `problem` events) — which does not exist yet either, since the only endpoint today (`POST /api/v1/assistant/chat`) is a synchronous JSON request/response with an in-memory conversation store.

This is deliberately a two-part slice (tool orchestration + SSE transport) rather than two separate WOs, because the `draft`/`problem` events ADR-0004 mandates have nowhere to go without the transport existing — splitting them would leave one half permanently unverifiable. The existing `POST /api/v1/assistant/chat` endpoint is untouched and keeps working exactly as today (additive-first versioning, AGENTS.md 1.4).

**Named gap — authorization (read this before Task 1):** ADR-0004 §2 has the Assistant relay the caller's own JWT downstream rather than use a service credential, and Task 1 below follows that pattern for the new Catalog call. But `docker/keycloak/realm-export.json`'s `purchase-management` realm role — the only role composited to `order.write`, which is what `intents.json`'s `commerce.order.place` intent already requires — is **not** composited to `catalog.read`, which is what `GET /api/v1/products/sku/{sku}` requires (`@PreAuthorize("hasAuthority('PERM_catalog.read')")` on `ProductController`). Implemented naively, a `purchase-management`-only caller gets `stage_order_draft` rejected with `403` on every call, while still passing tests run under the all-permission `admin`/`testuser` fixtures. This is a real gap, not an edge case — it is resolved explicitly in **Task 10**, which must land in the same PR as Task 1 (a `CatalogRestClient` with no matching role grant is not shippable).

**Constraint Checklist:**
- [ ] `stage_order_draft` is a **local** tool, intercepted inside `apps/polaris-assistant` before MCP dispatch — never a new MCP tool on `apps/polaris`. Writing an `AssistantOrderDraft` row from inside `apps/polaris`'s `OrderMcpTools` would mean the Order/Catalog app writing to a table it doesn't own; keeping the interception local to the Assistant context is what keeps this a single-bounded-context slice (Principle 2.2).
- [ ] Stock verification is **read-only**: only `GET /api/v1/products/sku/{sku}` is called, never any write endpoint, never `OrderService` directly.
- [ ] The `purchase-management` realm role can actually call `GET /api/v1/products/sku/{sku}` end-to-end (Task 10) — verified with a JWT fixture holding **only** that role, not `admin`/`testuser`.
- [ ] A `stage_order_draft` call carrying an existing `draft_id` **updates** that row in place (new price/quantity snapshot, refreshed `expires_at`) instead of inserting a second row for the same conversational draft.
- [ ] A rejected (insufficient-stock) staging attempt persists **nothing** — matches EM-001 §1 frame 04 ("the draft is not persisted") — whether it's a fresh stage or an attempted update of an existing draft (the existing draft, if any, is left unchanged).
- [ ] The existing `POST /api/v1/assistant/chat` endpoint's request/response contract is unchanged.
- [ ] 100% unit/integration test pass (`mvn clean test -pl apps/polaris-assistant`).

---

## 2. Detailed Technical Tasks

### Task 1: `CatalogRestClient` — Typed Client for the Published Catalog Contract
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/CatalogRestClient.java`

A thin `RestClient` (Spring 6 / Boot 4, already on the classpath — no new dependency) wrapper around `GET /api/v1/products/sku/{sku}`:

```java
public record CatalogProductView(String sku, String name, java.math.BigDecimal price, Integer stockQuantity, Boolean isAvailable) {}

public interface CatalogRestClient {
    Optional<CatalogProductView> getBySku(String sku); // empty on 404
}
```

Base URL: new config property `polaris.core.rest.base-url` (default `http://localhost:8080`), mirroring the existing `polaris.mcp.core.url` pattern in `PolarisMcpProperties`. In the docker profile, set `POLARIS_CORE_REST_BASE_URL: http://polaris:8080` in `docker-compose.yml`'s `polaris-assistant` service (sibling to the existing `POLARIS_MCP_CORE_URL`). Forward the caller's Bearer token on every request via `UserContext.resolveBearerToken()` (already implemented, already used identically by `HttpPolarisMcpClient.buildJsonRpcRequest` — reuse it, don't reimplement token propagation).

**This relay pattern is exactly what makes Task 10 mandatory.** `GET /api/v1/products/sku/{sku}` requires `PERM_catalog.read`; the realistic caller identity relayed through here (a `purchase-management`-only staff/service account) does not hold it today. Do not treat this task as complete without Task 10 landing in the same change.

### Task 2: Shared `InsufficientStockActions` Helper (small, additive, in `libs/polaris-common`)
**File (new):** `libs/polaris-common/src/main/java/vn/danang/polaris/web/exception/InsufficientStockActions.java`

This WO is where the first `actions[]` consumer that isn't `GlobalExceptionHandler` shows up, so the action-list construction (currently inlined at [`GlobalExceptionHandler.java:76-91`](../../../libs/polaris-common/src/main/java/vn/danang/polaris/web/exception/GlobalExceptionHandler.java)) is extracted to a shared static helper **now**, including the `remove_item` action WO-022 is separately chartered to add (PRD-003 FR-5 applies uniformly to every surface — there is no reason for the newer surface to launch without it just because of WO numbering):

```java
public final class InsufficientStockActions {
    private InsufficientStockActions() {}

    /** adjust_quantity (if availableQuantity > 0) + search_alternatives + remove_item, in that order. */
    public static List<Map<String, Object>> build(InsufficientStockException ex) { ... }
}
```

`InsufficientStockException` already lives in `libs/polaris-common` (a shared-kernel value type, not owned by Order or Catalog specifically — see its package `vn.danang.polaris.web.exception`, and note `PolarisAssistantApp` already explicitly component-scans `vn.danang.polaris.web.exception` to pick up `GlobalExceptionHandler`). Constructing/using it from the Assistant context is reuse of a shared kernel type, not a cross-context boundary crossing.

**This WO is the canonical owner of this helper.** Build order is fixed at WO-019 → WO-020 → WO-021 → WO-022 (matching the WO numbers), so this file is created here, once. WO-022 Task 2 retrofits `GlobalExceptionHandler.handleInsufficientStockException` to call it instead of its current inline action list, and WO-022 Task 4/5 threads it across the MCP boundary for `OrderMcpTools`/`PolicyToolManager` — both reuse this exact file verbatim and must not create a second copy. This is what gives the three-action shape (`adjust_quantity`/`search_alternatives`/`remove_item`) exactly one implementation across every surface (REST `ProblemDetail`, MCP `CallToolResult.structuredContent`, and this WO's `problem` SSE event).

### Task 3: Local Tool Schema — `stage_order_draft`
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/StageOrderDraftTool.java`

Tool name constant `stage_order_draft` (matches the tool name ADR-0004 §4.A's "Provider Abstraction Contract" lists as `stage_order`, spelled out fully for clarity in code). Schema:

```json
{
  "type": "object",
  "properties": {
    "draft_id": { "type": "string", "description": "Existing draft ID to update in place (e.g. after adjusting quantity or removing an item). Omit to start a new draft." },
    "customer_id": { "type": "integer", "description": "Numeric customer ID (use this or customer_name)" },
    "customer_name": { "type": "string", "description": "Customer name for fuzzy lookup, same semantics as place_order" },
    "items": {
      "type": "array",
      "items": { "type": "object", "properties": { "sku": {"type": "string"}, "quantity": {"type": "integer"} }, "required": ["sku", "quantity"] }
    }
  },
  "required": ["items"]
}
```

### Task 4: `DraftStagingService` — the Orchestration Itself
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/DraftStagingService.java`

```java
public sealed interface StageOutcome {
    record Staged(AssistantOrderDraft draft, List<DraftItemSnapshot> items) implements StageOutcome {}
    record Rejected(String sku, int requested, int available, List<Map<String,Object>> actions) implements StageOutcome {}
}

public StageOutcome stage(String sessionId, Long customerId, @Nullable String draftId, List<OrderItemRequest> items);
```

Logic:
1. Resolve/create the owning `AssistantSession` (upsert by `sessionId`: if absent, insert `status=ACTIVE`; per ADR-0004 §3.A, if it exists but is `CONFIRMED`/`EXPIRED`/`CANCELLED`, transition it back to `ACTIVE` on any new inbound message — this transition belongs here since staging is the entry point that reacts to a new user turn).
2. For each requested item, call `CatalogRestClient.getBySku(sku)`. Missing SKU → surface as a `Rejected` with `available=0` (re-using the same problem shape; no separate "not found" case needs inventing here — zero-available already renders the right remedies minus `adjust_quantity`, which `InsufficientStockActions.build` already omits when `availableQuantity <= 0`).
3. If **any** item's `requested > stockQuantity`, construct `new InsufficientStockException(sku, requested, available)` for the **first** failing item, call `InsufficientStockActions.build(ex)`, and return `Rejected` — **do not** touch the database (Constraint Checklist above). If `draftId` was supplied, the existing row is left exactly as it was.
4. If all items pass: snapshot `DraftItemSnapshot(sku, quantity, price, price.multiply(quantity))` per item (locking the ADR-0004 "price at staging time" guarantee for the duration of the TTL — see WO-021's Known Limitation note on what this guarantee does *not* cover), compute `totalAmount`, and:
   - If `draftId` is null, or non-null but `findByIdAndSessionId` returns empty: **insert** a new `AssistantOrderDraft` (`id = "dft-" + UUID.randomUUID()`, `status=WAITING_CONFIRMATION`, `expiresAt = now + 15m` — externalize the 15-minute TTL as `polaris.assistant.draft.ttl` (default `PT15M`), not a literal, so WO-021's expiration sweep and this insert read the same config value).
   - Else: **update** the existing row's `itemsJson`/`totalAmount`/`expiresAt` (refreshed) in place, leaving its `id` unchanged. This is the "insert or update-by-draftId" behavior.
   - Transition the owning session to `WAITING_CONFIRMATION`.
5. Return `Staged`.

### Task 5: `ToolResult` — Add an `actions` Field (canonical definition; WO-022 reuses this verbatim)
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/ToolResult.java`

This WO is where the first rejection carrying structured remedies is produced (Task 4's `Rejected` outcome), so the field is added here, not deferred to WO-022. Add a fifth record component:
```java
public record ToolResult(
        ToolCall toolCall, String result, Status status, String errorDescription,
        @JsonProperty("actions") List<Map<String, Object>> actions
) { ... }
```
Add a defaulting compact constructor and a new overload so **every existing call site keeps compiling unchanged**:
```java
public static ToolResult error(ToolCall toolCall, String error, String errorDescription, List<Map<String,Object>> actions) {
    return new ToolResult(toolCall, error, Status.ERROR, errorDescription, actions);
}
// existing 2- and 3-arg success/denied/error factories all delegate here with actions = null
```
`actions` is `null`/absent (via the existing `@JsonInclude(NON_NULL)`) for every result that isn't a structured remedy. WO-022 Task 3/5 reuses this exact field and overload for the `place_order` MCP path — it must not re-declare or duplicate this record diff.

### Task 6: Local Tool Interception in `PolicyToolManager`
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/PolicyToolManager.java`

- `discoverAllTools()`: append `StageOrderDraftTool`'s schema to the list returned by `polarisMcpClient.listAvailableTools()` (local tools ∪ remote tools — the model sees one merged list; ADR-0004 §4.A already lists `stage_order` alongside the other domain tools as if there's only one tool surface).
- `handleToolCalls(...)`: before the existing policy-check → concurrent-remote-execute pipeline, partition `toolCalls` by name. Route `stage_order_draft` calls to `DraftStagingService.stage(...)` (still through `checkPolicy` first — the policy/intent gate must not be bypassed just because execution is local) and wrap the `StageOutcome` in a `ToolResult`:
  - `Staged` → `ToolResult.success(toolCall, humanReadableSummary)`, with a `draft` widget payload attached (see Task 8) instead of `actions`.
  - `Rejected` → `ToolResult.error(toolCall, humanReadableSummary, semanticNote, actions)` using the four-argument `ToolResult.error(...)` overload from Task 5 above.
- Remaining (non-`stage_order_draft`) calls keep going through `executeConcurrently`/`polarisMcpClient.callTool` exactly as today.

### Task 7: SSE Streaming Endpoint
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/web/AssistantChatController.java` (add a method; existing `chat(...)` method is untouched)

```java
@PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamMessage(@PathVariable String sessionId,
                                 @Valid @RequestBody StreamMessageRequest request,
                                 Principal principal)
```

New DTO **File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/dto/StreamMessageRequest.java` — `record StreamMessageRequest(@NotBlank String content)`, matching ADR-0004 §1.A's request body (`{"content": "..."}`) literally rather than reusing `ChatMessageRequest`'s `message` field name, since `sessionId` is now a path variable, not a body field.

### Task 8: `AssistantChatService.streamTurn` — SSE-Emitting Variant of the ReAct Loop
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java` (new public method; existing `sendMessage(...)` is untouched and keeps backing `/chat`)

Reuses the same intent-resolution/ReAct-loop machinery as `sendMessage`, but instead of returning one `ChatMessageResponse`, emits SSE frames on the supplied `SseEmitter` as the loop progresses:

| SSE `event` | Emitted when | `data` shape |
|---|---|---|
| `thought` | before each model call | `{"step":"<phase>","message":"..."}` |
| `token` | on the model's final text (chunked if the provider streams; one frame for `DeterministicRuleModelClient`, noted as an accepted simplification) | `{"delta":"..."}` |
| `draft` | a `stage_order_draft` tool call returns `Staged` | `{"draftId":"...","status":"WAITING_CONFIRMATION","expiresAt":"...","items":[{"sku":"...","quantity":2,"unitPrice":24.90,"lineTotal":49.80}],"totalAmount":49.80}` — built from `DraftItemSnapshot` (WO-019 Task 5); this is `AssistantOrderDraftResponse` minus its `confirmedOrderNumber`/`sessionId` fields (WO-021 Task 4 defines the full DTO this is a projection of) |
| `problem` | a `stage_order_draft` tool call returns `Rejected` | `{"type":"https://polaris.local/errors/out-of-stock","sku":"...","requested_quantity":10,"available_quantity":5,"remedy":"...","actions":[...]}` — same shape `InsufficientStockActions`/`GlobalExceptionHandler` produce for REST clients (Task 2) |
| `done` | loop terminates | `{"sessionId":"...","status":"<current AssistantSessionStatus>"}` |

Heartbeat: `: keep-alive\n\n` comment every 15s via a `ScheduledExecutorService` task tied to the `SseEmitter`'s lifecycle (`onCompletion`/`onTimeout`/`onError` cancel it), per ADR-0004 §1.A.

### Task 9: `intents.json` — Route the Primary Chat Surface to Staging
**File:** `apps/polaris-assistant/src/main/resources/intents.json`

Add `"stage_order_draft"` to `commerce.order.place`'s `allowedTools` (alongside the existing `place_order`, `search_customers_by_name`). `place_order` is deliberately **not removed**: it remains the tool external headless/MCP clients (ADR-0004's "Secondary Extensibility Architecture → Topology C") use when there is no human-in-the-loop web chat surface to confirm a draft against — WO-022 is what keeps that path's remedy actions correct. Whether the *deterministic/cloud model* prompt itself is steered to prefer `stage_order_draft` over `place_order` on the web chat surface is a model-prompting concern, not a contract change, and is left to the implementer's judgment during this WO.

### Task 10: Keycloak Realm Fix — Grant `purchase-management` Read Access to Catalog
**File:** `docker/keycloak/realm-export.json`

**Chosen resolution:** extend the `purchase-management` (and its underscore-alias `purchase_management`) realm role's composite client roles to include `catalog.read`, alongside its existing `order.read`/`order.write`. Concretely, both role entries' `composites.client["polaris-api"]` arrays gain `"catalog.read"`:

```json
{
  "name": "purchase-management",
  "composites": { "client": { "polaris-api": ["order.read", "order.write", "catalog.read"] } }
}
```
(and identically for the `purchase_management` alias entry, matching the existing dual-naming convention already used for `product-catalog`/`product_catalog` etc.)

**Why this option over the alternatives named in review:**
- *Rejected — service-to-service credential for this one call:* would mean `CatalogRestClient` uses a machine credential instead of relaying the caller's JWT, breaking ADR-0004 §2.A's "every internal domain call initiated by the agency engine inherits the exact SecurityContext of the human caller" invariant (Principle 1.2, zero-privilege-escalation). That is a larger, security-review-worthy architectural change (a new OAuth2 client, a new credential to rotate, a documented exception to the token-relay rule) to solve what is fundamentally a narrow, correctly-scoped permission gap. It would need its own ADR, not a paragraph in this WO.
- *Chosen — extend the role:* `OrderService.placeOrder` already performs this same stock check today, in-process, gated by nothing but `order.write` (no HTTP hop ever asked Catalog's permission model anything). Making that same check explicit over REST should not silently impose a *new*, stricter permission requirement that didn't exist for the equivalent in-process capability. Granting `catalog.read` — already the least-privileged read scope in the system, the same one `product-catalog` holds — to the role that places orders is a narrow, low-blast-radius change: no `catalog.write`, no `inventory.*`, no other scope is added.

**Blast radius:** every caller holding `purchase-management` (staff placing orders on a customer's behalf, and now the Assistant relaying such a caller's token) gains read-only product/catalog visibility. No write capability changes anywhere. This is a realm-config change to a file already checked into this repo (`docker/keycloak/realm-export.json`, loaded at container bootstrap by `docker-compose.yml`'s `keycloak` service) — in a real deployed Keycloak instance (not this repo's local/CI bootstrap fixture), the equivalent change would be applied via Keycloak's own admin API/realm migration tooling, not a JSON file edit; call this out in the implementation PR's description so a deployed-environment operator knows to mirror it.

---

## 3. Given/When/Then Acceptance Criteria

1. **Given** a session with no prior draft, **when** the model calls `stage_order_draft` with `items=[{sku:"NG-WATCH-01", quantity:10}]` and Catalog reports `stockQuantity=5`, **then** no `AssistantOrderDraft` row is created, and the client receives an SSE `problem` event with `available_quantity:5` and `actions` containing `adjust_quantity`, `search_alternatives`, and `remove_item`.
2. **Given** the same session, **when** the model retries `stage_order_draft` with `items=[{sku:"NG-WATCH-01", quantity:5}]` (no `draft_id` — nothing was persisted from step 1), **then** a new `AssistantOrderDraft` row is inserted with `status=WAITING_CONFIRMATION`, `expires_at ≈ now + 15m`, and the client receives an SSE `draft` event carrying that `draftId`.
3. **Given** an existing `WAITING_CONFIRMATION` draft `dft-abc`, **when** `stage_order_draft` is called with `draft_id:"dft-abc"` and a changed quantity that still fits in stock, **then** the same `dft-abc` row is updated (item snapshot, total, and `expires_at` refreshed) — no second row is created.
4. **Given** an existing `WAITING_CONFIRMATION` draft `dft-abc`, **when** `stage_order_draft` is called with `draft_id:"dft-abc"` and a quantity that now exceeds stock, **then** `dft-abc` is returned unchanged (still the old quantity/total/expiry) and the client receives a `problem` event.
5. **Given** `GET /api/v1/products/sku/{sku}` is unreachable (Catalog down), **when** `stage_order_draft` is called, **then** the tool call fails closed (`ToolResult.error`, no draft mutation) rather than silently assuming stock is sufficient.
6. **Given** a client `POST`s to `/api/v1/assistant/sessions/{sessionId}/messages` with `Accept: text/event-stream`, **when** the turn completes with no tool calls, **then** the response is `Content-Type: text/event-stream` carrying at minimum one `token` frame and a terminal `done` frame — and `POST /api/v1/assistant/chat` continues to behave exactly as before this WO (regression check).
7. **Given** a caller JWT holding **only** the `purchase-management` realm role (not `admin`, not `testuser`) — the realistic least-privileged persona for this feature — **when** `stage_order_draft` is called for an in-stock item, **then** `CatalogRestClient.getBySku` succeeds (`200`, not `403`) and the draft stages normally. **Given** the same JWT **before** Task 10's realm change, this call would 403 — the test must fail on the pre-Task-10 realm export and pass after, or it isn't actually exercising the fix.
8. **Given** a caller JWT holding only `product-catalog` (catalog permissions, no order permissions), **when** `stage_order_draft` is called, **then** the request is rejected before reaching `CatalogRestClient` at all — by the existing `requiredScope: order.write` gate on the `commerce.order.place` intent (Task 10 only adds a permission, it must not widen who can reach this tool in the first place).

---

## 4. Verification & Acceptance Criteria

```bash
mvn clean test -pl apps/polaris-assistant
mvn clean test
```
Manual/Playwright SSE smoke check (per the arch-agent sign-off workflow): open a chat session, send a message that stages an out-of-stock quantity, confirm the browser DevTools Network tab shows an `event: problem` frame with `actions[]`, then retry with a valid quantity and confirm an `event: draft` frame appears with a stable `draftId`. Re-run this smoke check specifically authenticated as the `purchasemanagement` seeded test user (not `testuser`) to confirm Task 10's realm change actually closes the gap end-to-end, not just in a unit test with a hand-built JWT fixture.
