# Front half of Order-to-Cash
A consumer-electronics retailer puts one AI agent in front of two groups of people:
- Shoppers (ROLE_USER) who chat on the web to find products, buy them, check order status and cancel orders.
- Store and call-center staff (ROLE_STAFF) who use the same agent to order and cancel on behalf of a customer, with their operator_id recorded for audit.

See [`prds/`](prds/) for the detailed product requirements (personas, use cases, acceptance criteria) behind each capability referenced below.

## Business Process: Shopper Search & Order Placement (BPMN)

Happy-path flow for a shopper who chats with the AI Assistant to search the catalog and place an order. 
```mermaid
flowchart TD
    subgraph Shopper["Shopper"]
        direction TB
        S_Start(("Start:<br/>wants to shop"))
        S_Ask["Ask assistant to find a product<br/>e.g. 'fast chargers under $30'"]
        S_Review["Review product results"]
        S_Request["Ask to order selected item(s)"]
        S_Confirm["Click 'Submit Order'"]
        S_End(("End:<br/>order placed"))
    end

    subgraph Assistant["AI Assistant (Orchestrator)"]
        direction TB
        A_Intent["Resolve intent & entities"]
        A_Present["Render product cards"]
        A_Stage["Stage order draft<br/>(pricing snapshot, 15-min TTL)"]
        A_Draft["Present draft card<br/>(items, total)"]
        A_Gate{"Human-in-the-Loop<br/>Confirmation Gate"}
        A_Execute["Execute order<br/>(Idempotency-Key)"]
        A_ConfirmCard["Render confirmed order card"]
    end

    subgraph Catalog["Catalog Context"]
        direction TB
        C_Search["Search products &<br/>verify live stock"]
    end

    subgraph Order["Order Context"]
        direction TB
        O_Verify["Re-verify stock & price"]
        O_Create["Create order (PLACED)<br/>& deduct stock atomically"]
    end

    S_Start --> S_Ask --> A_Intent --> C_Search --> A_Present --> S_Review
    S_Review --> S_Request --> A_Stage --> O_Verify --> A_Draft --> A_Gate
    A_Gate -->|"Confirmed"| S_Confirm --> A_Execute --> O_Create --> A_ConfirmCard --> S_End
```

1. **Search:** Shopper asks the assistant to find a product; the assistant resolves intent/entities and queries the Catalog Context for matching products with live stock, then renders product cards.
2. **Order request:** Shopper asks to order one or more of the returned items. The assistant stages an order draft, having the Order Context re-verify stock and price, then presents an itemized draft with a total.
3. **Confirmation gate:** Per [PRD-003](prds/PRD-003-web-chat-ai-assistant.md) FR-3, the assistant never places an order automatically. It waits for the shopper to explicitly confirm (button click or affirmative message).
4. **Placement:** On confirmation, the assistant executes the order with an idempotency key; the Order Context creates the order in `PLACED` status and atomically deducts stock; the assistant renders the confirmed order card back to the shopper.

## Exception Path: Out-of-Stock at Order Staging

Branches off step 2 above (order request), when the requested quantity exceeds available stock. See [PRD-003](prds/PRD-003-web-chat-ai-assistant.md) §3.5 and [PRD-002](prds/PRD-002-comprehensive-order-apis.md) Scenario 2.

```mermaid
flowchart TD
    subgraph Shopper["Shopper"]
        direction TB
        S_Request["Ask to order item(s)"]
        S_Remedy["Pick a remedy:<br/>adjust qty / search alternatives / remove item"]
    end

    subgraph Assistant["AI Assistant (Orchestrator)"]
        direction TB
        A_Stage["Stage order draft"]
        A_Problem["Render RFC 7807 Problem Card<br/>(shortfall + remedy buttons)"]
        A_Apply["Apply chosen remedy to draft"]
    end

    subgraph Order["Order Context"]
        direction TB
        O_Check{"Sufficient stock<br/>for requested qty?"}
        O_Reject["Reject:<br/>InsufficientStockException<br/>(requested, available)"]
    end

    S_Request --> A_Stage --> O_Check
    O_Check -->|"No"| O_Reject --> A_Problem --> S_Remedy --> A_Apply --> A_Stage
    O_Check -->|"Yes"| Resume(("Resume happy path:<br/>present draft card"))
```

1. **Detect shortfall:** While staging the draft, the Order Context checks requested quantity against live stock and rejects with a structured `InsufficientStockException` (requested vs. available units) instead of a generic error.
2. **Remediate:** The assistant renders a Problem Card with one-click remedies: adjust quantity to what's available, search for alternatives, or remove the item.
3. **Retry:** Whichever remedy the shopper picks, the assistant re-applies it to the draft and re-stages it, looping back into the same stock check. Once stock is sufficient, the flow rejoins the happy path at the draft card / confirmation gate.

## Business Process: Staff Cancellation on a Shopper's Behalf (BPMN)

Store/call-center staff (`ROLE_STAFF`) cancel an order for a customer they're assisting. The same Human-in-the-Loop Confirmation Gate applies as for self-service — staff get *broader authorization* (any assigned `customer_id`), not an exemption from confirmation — and every mutation is tagged with the staff member's `operator_id` for audit. See [PRD-003](prds/PRD-003-web-chat-ai-assistant.md) §3.6, §4 FR-6 and [PRD-002](prds/PRD-002-comprehensive-order-apis.md) FR-6.

```mermaid
flowchart TD
    subgraph Staff["Store/Call-Center Staff"]
        direction TB
        T_Start(("Start:<br/>customer wants to cancel"))
        T_Ask["Ask assistant to cancel order<br/>for customer_id, order number"]
        T_Confirm["Click 'Confirm Cancellation'"]
        T_End(("End:<br/>order cancelled"))
        T_Rejected(("End:<br/>cancellation rejected"))
    end

    subgraph Assistant["AI Assistant (Orchestrator)"]
        direction TB
        A_Auth["Resolve intent &<br/>validate staff authorization for customer_id"]
        A_Review["Render Cancellation Review Card<br/>(items, restock notice)"]
        A_Gate{"Human-in-the-Loop<br/>Confirmation Gate"}
        A_Execute["Execute cancellation<br/>(operator_id recorded for audit)"]
        A_ConfirmCard["Render cancellation<br/>confirmation card"]
        A_Problem["Render RFC 7807 Problem Card<br/>(non-cancellable state + return/support info)"]
    end

    subgraph Order["Order Context"]
        direction TB
        O_Check{"Order in PLACED<br/>or CONFIRMED?"}
        O_Cancel["Transition to CANCELLED<br/>& restore inventory atomically"]
        O_Reject["Reject:<br/>409 business conflict<br/>(terminal/fulfillment state)"]
    end

    T_Start --> T_Ask --> A_Auth --> O_Check
    O_Check -->|"Yes"| A_Review --> A_Gate
    A_Gate -->|"Confirmed"| T_Confirm --> A_Execute --> O_Cancel --> A_ConfirmCard --> T_End
    O_Check -->|"No"| O_Reject --> A_Problem --> T_Rejected
```

1. **Request & authorize:** Staff asks the assistant to cancel an order, specifying the `customer_id` they're assisting. The assistant validates the staff member is authorized to act on that customer (never the shopper's own identity check used for `ROLE_USER`), closing the IDOR gap between staff acting on behalf of others and shoppers acting for themselves.
2. **Eligibility check:** The Order Context checks the order's lifecycle state. Orders in `PROCESSING`, `SHIPPED`, `DELIVERING`, `DELIVERED`, or already `CANCELLED` are rejected with an RFC 7807 business conflict; the assistant surfaces this as a Problem Card with return/support guidance and the flow ends there — no gate, no audit entry, because no mutation occurred.
3. **Review & confirmation gate:** For eligible (`PLACED`/`CONFIRMED`) orders, the assistant renders a Cancellation Review Card (items, restock notice) and — exactly as for shopper self-service — waits for the staff member to explicitly click "Confirm Cancellation." Staff privilege widens *whose* order can be cancelled, not *whether* confirmation is required.
4. **Execute & audit:** On confirmation, the assistant executes the cancellation with the staff member's `operator_id` recorded in the order's audit metadata; the Order Context transitions the order to `CANCELLED` and atomically restores inventory; the assistant renders the confirmation back to staff.
