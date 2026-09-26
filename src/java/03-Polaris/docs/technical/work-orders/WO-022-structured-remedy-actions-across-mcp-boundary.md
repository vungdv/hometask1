# Slice Work Order: WO-022
## Title: Structured Remedy Actions Across the MCP Boundary (incl. `remove_item`)

- **Target Modules:** `apps/polaris` (MCP tool response shape — producer side of the seam), `apps/polaris-assistant` (consuming side of the seam), `libs/polaris-common` (shared helper, additive)
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0004](../decisions/0004-web-chat-ai-assistant-architecture.md) Decision Outcome (dual-topology: Topology C headless/MCP clients get identical domain capabilities to Topology B's web chat, "without backend duplication")
- **Product Reference:** [PRD-003](../../business/prds/PRD-003-web-chat-ai-assistant.md) FR-5 (Remove Item remedy)
- **Event Model Reference:** [EM-001](../event-models/EM-001-order-staging-out-of-stock-exception.md) §2 frame 05 / §3 Gap Analysis rows "Structured remedy actions reach the shopper" and "'Remove Item' remedy"
- **Bounded Context:** this is a **seam change**, not a single-context change — see Principle 2.1 "Seam Splitting" call-out below. It touches the `place_order` MCP tool's response contract, which is jointly owned across `apps/polaris` (producer) and `apps/polaris-assistant` (consumer). No database, no other endpoint, no other tool is touched.
- **Cross-Context Contracts Consumed:** none new. This WO does not add a new REST/CloudEvents contract — it enriches the existing, already-published MCP `CallToolResult` wire shape for the `place_order` tool call (MCP is itself the published contract here, per ADR-0004's Topology C).
- **Status:** PROPOSED

---

## 1. Objective & Scope

EM-001 §2 frame 05 draws the divergence explicitly: a direct REST client of `OrderController` gets a full RFC 7807 `ProblemDetail` with a structured `actions[]` array on insufficient stock; the AI Assistant, going through `OrderMcpTools.placeOrder`'s catch block, only gets a formatted **plain-text** sentence. This WO threads the same structured remedies through the MCP boundary, and adds the missing `remove_item` action (PRD-003 FR-5) that neither surface has today.

**Why this is a seam, not two independent per-context changes (Principle 2.1):** the fix requires changing what `apps/polaris`'s `OrderMcpTools.placeOrder` *returns* and what `apps/polaris-assistant`'s `PolicyToolManager`/`ToolResult` *reads* from that same return value — one wire shape, two files in two different Maven modules that must agree on it. Rather than splitting this into "WO-022a: apps/polaris" and "WO-022b: apps/polaris-assistant" (which would let the two drift if merged out of order), the shape is specified once, in full, right here, and both sides implement against this document — the seam contract this WO defines *is* the artifact that keeps the two modules honest with each other, per Principle 2.1's "explicit, contract-tested interfaces."

**Constraint Checklist:**
- [ ] `place_order`'s plain-text `TextContent` behavior for a **successful** call is unchanged — this WO only touches the error/rejection path.
- [ ] The `actions[]` shape (fields, ordering: `adjust_quantity` (if available>0), `search_alternatives`, `remove_item`) is byte-for-byte identical to what a direct REST client of `OrderController` receives — verified by both sides calling the same shared helper (Task 1), not by two independent implementations that happen to agree today.
- [ ] Every existing call site of `ToolResult.error(...)`/`ToolResult.denied(...)` (there are several in `PolicyToolManager` unrelated to stock) keeps compiling unchanged — the new `actions` field is additive with a defaulting overload, per AGENTS.md 3.3 "Preserve Contracts via Facades."
- [ ] 100% unit test pass across both `apps/polaris` and `apps/polaris-assistant`.

---

## 2. Detailed Technical Tasks

### Task 1: Shared `InsufficientStockActions` Helper
**File:** `libs/polaris-common/src/main/java/vn/danang/polaris/web/exception/InsufficientStockActions.java`

If WO-020 has already landed, this file already exists with `remove_item` included — use it as-is, do not create a second copy. If this WO lands first, create it:

```java
package vn.danang.polaris.web.exception;

import java.util.*;

public final class InsufficientStockActions {
    private InsufficientStockActions() {}

    public static List<Map<String, Object>> build(InsufficientStockException ex) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (ex.getAvailableQuantity() > 0) {
            actions.add(action("Adjust Quantity to " + ex.getAvailableQuantity(), "adjust_quantity",
                    Map.of("sku", ex.getSku(), "quantity", ex.getAvailableQuantity())));
        }
        actions.add(action("Search Alternatives", "search_alternatives", Map.of("query", ex.getSku())));
        actions.add(action("Remove Item", "remove_item", Map.of("sku", ex.getSku())));
        return actions;
    }

    private static Map<String, Object> action(String label, String actionId, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("action", actionId);
        m.putAll(extra);
        return m;
    }
}
```

### Task 2: Retrofit `GlobalExceptionHandler` to Use the Shared Helper
**File:** `libs/polaris-common/src/main/java/vn/danang/polaris/web/exception/GlobalExceptionHandler.java`

Replace the inline action-building block at [lines 76–91](../../../libs/polaris-common/src/main/java/vn/danang/polaris/web/exception/GlobalExceptionHandler.java) with:
```java
problem.setProperty("actions", InsufficientStockActions.build(ex));
```
This is the change that actually delivers `remove_item` to direct REST clients — today's `handleInsufficientStockException` only builds `adjust_quantity`/`search_alternatives`. No other line in this method changes; `title`, `type`, `sku`, `requested_quantity`, `available_quantity`, `remedy` are all untouched, so this is a pure superset addition to the existing `ProblemDetail` response body (additive, non-breaking).

### Task 3: `ToolResult` — Add an `actions` Field
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/ToolResult.java`

Add a fifth record component:
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
`actions` is `null`/absent (via the existing `@JsonInclude(NON_NULL)`) for every result that isn't a structured remedy — this field means nothing for `SUCCESS`/`DENIED` results today and is reserved exclusively for the insufficient-stock case this WO and WO-020 populate.

### Task 4: `OrderMcpTools.placeOrder` — Emit `structuredContent`
**File:** `apps/polaris/src/main/java/vn/danang/polaris/mcp/OrderMcpTools.java`

Replace the `catch (InsufficientStockException ex)` block at [lines 447–453](../../../apps/polaris/src/main/java/vn/danang/polaris/mcp/OrderMcpTools.java) — the plain-text-only branch EM-001 §2 frame 05 calls out — with:

```java
} catch (InsufficientStockException ex) {
    String errorMsg = String.format(
            "Insufficient stock for product '%s'. Requested: %d, available: %d. Remedy: Reduce order quantity for '%s' to %d or fewer units.",
            ex.getSku(), ex.getRequestedQuantity(), ex.getAvailableQuantity(), ex.getSku(), ex.getAvailableQuantity()
    );
    Map<String, Object> structured = new LinkedHashMap<>();
    structured.put("type", "https://polaris.local/errors/out-of-stock");
    structured.put("sku", ex.getSku());
    structured.put("requested_quantity", ex.getRequestedQuantity());
    structured.put("available_quantity", ex.getAvailableQuantity());
    structured.put("actions", InsufficientStockActions.build(ex));
    return McpSchema.CallToolResult.builder()
            .addTextContent(errorMsg)          // unchanged human-readable text, for any MCP client that ignores structuredContent
            .structuredContent(structured)     // new — MCP SDK 2.0.1's CallToolResult.Builder already supports this field
            .isError(true)
            .build();
}
```

`McpSchema.CallToolResult.Builder.structuredContent(Object)` already exists in the `mcp-core:2.0.1` SDK dependency this module already uses (confirmed against `io.modelcontextprotocol.spec.McpSchema` in the resolved jar) — no SDK version bump required.

### Task 5: `PolicyToolManager` — Read `structuredContent` Back Out
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/PolicyToolManager.java`

In `executeRemoteToolCall`, the `Boolean.TRUE.equals(mcpResult.isError())` branch currently only calls `extractText(mcpResult)`. Add extraction of `mcpResult.structuredContent()`'s `"actions"` entry (cast defensively — `structuredContent` is `Object`, coming back over JSON-RPC as a `Map`) and pass it into the new `ToolResult.error(toolCall, errorText, "Tool execution failure", actions)` overload from Task 3. If `structuredContent` is absent or unshaped (e.g. an older Polaris Core version, or a different failure with no actions), fall back to `actions = null` — never throw.

### Task 6: Surface `actions` into Conversation History
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java`

`toToolTurn(ToolResult toolResult)` currently only copies `toolResult.result()` into the `AssistantMessage.content` field. When `toolResult.actions() != null && !isEmpty()`, additionally set `widgetType = "PROBLEM_CARD"` and `widgetPayload = objectMapper.writeValueAsString(Map.of("actions", toolResult.actions()))` on that same `AssistantMessage`, mirroring how `toModelTurn` already uses `widgetPayload` for structured data. This makes the remedy actions available in conversation history for both the model's own next turn and (if WO-020 has landed) the SSE `problem` event WO-020 emits when the model's tool call is `stage_order_draft` rather than `place_order` — this WO does not itself add new SSE plumbing; it only guarantees the data is no longer thrown away between the MCP boundary and the rest of the Assistant context.

---

## 3. Given/When/Then Acceptance Criteria

1. **Given** a direct REST client calls `POST /api/v1/orders` for a quantity exceeding stock, **when** the response is inspected, **then** `actions[]` contains exactly three entries: `adjust_quantity`, `search_alternatives`, `remove_item` (regression: today only the first two exist).
2. **Given** the AI Assistant's model calls the `place_order` MCP tool for the same over-quantity request, **when** `OrderMcpTools.placeOrder` returns, **then** the `CallToolResult.isError()` is `true`, `content[0].text` is unchanged plain text, and `structuredContent.actions` contains the same three entries as (1), byte-for-byte.
3. **Given** that MCP result reaches `PolicyToolManager.executeRemoteToolCall`, **when** the resulting `ToolResult` is inspected, **then** `toolResult.actions()` is non-null and equal to the REST client's `actions[]` from (1).
4. **Given** an unrelated tool call denial (e.g. policy-denied `cancel_order`), **when** its `ToolResult` is constructed, **then** `actions()` is `null` and no existing test asserting the old 4-argument `ToolResult` shape breaks.
5. **Given** the resulting `ToolResult` is converted to conversation history, **when** `toToolTurn` runs, **then** the `AssistantMessage` has `widgetType="PROBLEM_CARD"` and a `widgetPayload` containing the three actions.

---

## 4. Verification & Acceptance Criteria

```bash
mvn clean test -pl libs/polaris-common,apps/polaris,apps/polaris-assistant
mvn clean test
```
Diff-check: `curl -X POST https://polaris.local/api/v1/orders ...` (over-quantity payload) and a chat message that triggers the equivalent `place_order` MCP call, and confirm the two `actions[]` arrays are structurally identical (same three action ids, same field names) — this is the seam contract test that actually proves Task 1's shared helper is doing its job rather than two hand-written lists that happen to match today.
