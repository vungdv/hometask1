# Assistant Circuit Breakers (TypeSafe & Gemini)

How Polaris Assistant degrades when its two model providers fail, and what changed in the existing components
to get there.

| TypeSafe (intent) | Gemini (reply) | Shopper sees |
|---|---|---|
| up | up | Normal turn (unchanged) |
| **down** | up | `200 OK`, turn pinned to `information.lookup.order.status`, reply starts with *"Polaris Assistant is having a problem right now, so I can only help with checking your order status."* |
| any | **down** | `503` Problem Details *"Polaris Assistant is temporarily down. Please come back later."* + `Retry-After` |

**Legend for all diagrams:** 🟩 green / `🟩 NEW` participants = new component, 🟨 yellow = new or changed
behaviour in an existing component. Anything without a colour is unchanged.

---

## 1. Component wiring: before vs after

The breakers are **decorators** (Facade pattern, AGENTS.md P3.3). They are `@Primary`, so every existing
injection point of `IntentClassifier` / `AssistantModelClient` now gets the guarded version. The provider
clients themselves are unchanged.

```mermaid
flowchart LR
    subgraph Before
        direction LR
        S1[AssistantChatService] --> F1[IntentResolutionFacade] --> R1[DefaultIntentResolver]
        R1 --> M1[IntentManager]
        R1 --> T1[TypeSafeIntentClassifier] --> TA1[(TypeSafe API)]
        S1 --> G1[GeminiAiModelClient] --> GA1[(Gemini API)]
    end

    subgraph After
        direction LR
        S2[AssistantChatService] --> F2[IntentResolutionFacade] --> R2[DefaultIntentResolver]
        R2 --> M2[IntentManager]
        R2 --> CBC[CircuitBreakingIntentClassifier] --> T2[TypeSafeIntentClassifier] --> TA2[(TypeSafe API)]
        S2 --> CBM[CircuitBreakingModelClient] --> G2[GeminiAiModelClient] --> GA2[(Gemini API)]
        CBC -.uses.-> REG[CircuitBreakerRegistry<br/>typesafe · gemini]
        CBM -.uses.-> REG
        REG -.metrics.-> MR[(Micrometer<br/>resilience4j.circuitbreaker.*)]
        C2[AssistantChatController] --> S2
        C2 -.503.-> EH[AssistantUnavailableExceptionHandler]
    end

    classDef added fill:#2e7d3233,stroke:#2e7d32,stroke-width:2px
    classDef changed fill:#f9a82533,stroke:#f9a825,stroke-width:2px
    class CBC,CBM,REG,EH,MR added
    class S2,R2 changed
```

---

## 2. Normal turn (both breakers CLOSED)

The behaviour is the same as before. The only addition is that each decorator records the outcome of each
provider call on its breaker.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Ctrl as AssistantChatController
    participant Chat as AssistantChatService
    participant Facade as IntentResolutionFacade
    participant Resolver as DefaultIntentResolver
    participant IM as IntentManager
    participant CBT as 🟩 NEW CircuitBreakingIntentClassifier
    participant CBG as 🟩 NEW CircuitBreakingModelClient
    participant TS as TypeSafeIntentClassifier
    participant GM as GeminiAiModelClient

    User->>Ctrl: POST /api/v1/assistant/chat
    Ctrl->>Chat: sendMessage(request, userId)
    Chat->>Facade: resolve(message, history)
    Facade->>Resolver: resolve(message, history, tools)
    Resolver->>IM: listIntents()
    IM-->>Resolver: taxonomy
    Resolver->>CBT: classify(message, history, intents)
    rect rgba(46,125,50,0.12)
        CBT->>CBT: tryAcquirePermission() ✔ CLOSED
        CBT->>TS: classify(...)
        TS-->>CBT: IntentClassification + ModelCall(succeeded)
        CBT->>CBT: onSuccess()
    end
    CBT-->>Resolver: classification (unchanged)
    Resolver->>IM: getIntent(intentId)
    IM-->>Resolver: IntentDefinition
    Note over Resolver: confidence ≥ threshold check<br/>(unchanged)
    Resolver-->>Chat: ResolvedIntent(intent, tools)

    loop ReAct loop (max 5)
        Chat->>CBG: generateResponse(history, tools, ctx)
        rect rgba(46,125,50,0.12)
            CBG->>CBG: tryAcquirePermission() ✔ CLOSED
            CBG->>GM: generateResponse(...)
            GM-->>CBG: ModelResponse + ModelCall(succeeded)
            CBG->>CBG: onSuccess()
        end
        CBG-->>Chat: ModelResponse (unchanged)
    end
    Chat-->>Ctrl: ChatMessageResponse (outcome=answered)
    Ctrl-->>User: 200 OK
```

---

## 3. TypeSafe down → degraded "order status only" turn

TypeSafe either fails (`ModelCall.failed`: I/O error, non-2xx, or an exception) or its breaker is OPEN
(then TypeSafe is not called at all). The decorator returns a **degraded** `IntentClassification` that pins the
configured `degraded-intent`. `IntentManager` is still asked for that intent's definition, so the turn only
gets the tools that intent allows (`get_order_status`). Gemini still writes the reply.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Ctrl as AssistantChatController
    participant Chat as AssistantChatService
    participant Resolver as DefaultIntentResolver
    participant IM as IntentManager
    participant CBT as 🟩 NEW CircuitBreakingIntentClassifier
    participant CBG as 🟩 NEW CircuitBreakingModelClient
    participant TS as TypeSafeIntentClassifier
    participant GM as GeminiAiModelClient

    User->>Ctrl: POST /chat "find me a charger"
    Ctrl->>Chat: sendMessage(...)
    Chat->>Resolver: resolve(...) via IntentResolutionFacade
    Resolver->>IM: listIntents()
    IM-->>Resolver: taxonomy
    Resolver->>CBT: classify(...)

    rect rgba(46,125,50,0.12)
        alt breaker CLOSED / HALF_OPEN
            CBT->>TS: classify(...)
            TS--xCBT: ModelCall(failed) e.g. 503 / connection refused
            CBT->>CBT: onError() (may trip breaker → OPEN)
        else breaker OPEN
            Note over CBT,TS: TypeSafe NOT called<br/>(fast fail, reason=circuit_open)
        end
        CBT-->>Resolver: IntentClassification.degraded(<br/>"information.lookup.order.status", reason, notice)
    end

    rect rgba(249,168,37,0.15)
        Note over Resolver: isDegraded() → skip confidence threshold<br/>pin the degraded intent
        Resolver->>IM: getIntent("information.lookup.order.status")
        IM-->>Resolver: definition (allowedTools = [get_order_status])
        Resolver-->>Chat: ResolvedIntent(order.status, tools=[get_order_status],<br/>degradedNotice)
    end

    loop ReAct loop
        Chat->>CBG: generateResponse(history, [get_order_status])
        CBG->>GM: generateResponse(...)
        GM-->>CBG: "Please share your order number."
        CBG-->>Chat: ModelResponse
    end

    rect rgba(249,168,37,0.15)
        Note over Chat: reply = degradedNotice + "\n\n" + model reply<br/>metric outcome = degraded
    end
    Chat-->>Ctrl: ChatMessageResponse
    Ctrl-->>User: 200 OK "Polaris Assistant is having a problem right now, ..."
```

---

## 4. Gemini down (alone or with TypeSafe) → 503 "temporarily down"

Without Gemini no reply can be produced at all, not even the degraded one. The decorator throws
`AssistantUnavailableException`, either because the call failed or because the breaker is OPEN (then Gemini is
not called). `AssistantChatService` records the turn as `unavailable` and persists nothing. A dedicated
advice, ordered ahead of the shared `GlobalExceptionHandler`, maps the exception to RFC 7807.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Ctrl as AssistantChatController
    participant Chat as AssistantChatService
    participant Resolver as DefaultIntentResolver
    participant CBT as 🟩 NEW CircuitBreakingIntentClassifier
    participant CBG as 🟩 NEW CircuitBreakingModelClient
    participant EH as 🟩 NEW AssistantUnavailableExceptionHandler
    participant TS as TypeSafeIntentClassifier
    participant GM as GeminiAiModelClient

    User->>Ctrl: POST /chat "where is my order ORD-1"
    Ctrl->>Chat: sendMessage(...)
    Chat->>Resolver: resolve(...)
    Resolver->>CBT: classify(...)
    CBT->>TS: classify(...) (skipped if OPEN)
    TS--xCBT: failed
    CBT-->>Resolver: degraded(order.status)
    Resolver-->>Chat: ResolvedIntent(degraded)

    Chat->>CBG: generateResponse(...)
    rect rgba(46,125,50,0.12)
        alt breaker CLOSED / HALF_OPEN
            CBG->>GM: generateResponse(...)
            GM--xCBG: ModelCall(failed) e.g. 503
            CBG->>CBG: onError() (may trip breaker → OPEN)
        else breaker OPEN
            Note over CBG,GM: Gemini NOT called (fast fail)
        end
        CBG--xChat: throw AssistantUnavailableException(retryAfter = open-state wait)
    end

    rect rgba(249,168,37,0.15)
        Note over Chat: metric outcome = unavailable<br/>no messages persisted
        Chat--xCtrl: rethrow
    end
    Ctrl--xEH: AssistantUnavailableException
    rect rgba(46,125,50,0.12)
        EH-->>User: 503 application/problem+json<br/>Retry-After: 30<br/>detail "Polaris Assistant is temporarily down. Please come back later."
    end
```

---

## 5. Breaker lifecycle (per provider)

One breaker per provider (`typesafe`, `gemini`), configured under `polaris.assistant.resilience.circuit-breaker`.

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN: failure rate ≥ 50%<br/>over last 10 calls (min 4)
    OPEN --> HALF_OPEN: after 30s<br/>(automatic)
    HALF_OPEN --> CLOSED: 2 trial calls OK
    HALF_OPEN --> OPEN: trial call fails
    note right of OPEN
        TypeSafe: pin order-status intent
        Gemini: 503 + Retry-After
        provider is not called
    end note
```

A result where the provider was **not consulted** (missing API key: local fallback or echo) passes through
unchanged and does not count toward the breaker.

---

## 6. Impact per component

| Component | Change | Impact |
|---|---|---|
| `IntentManager` | **None** | Still the source of the taxonomy (`listIntents`) and of the degraded intent's definition (`getIntent`). It decides which tools the degraded turn may use, so the degraded intent must exist in `intents.json` / Redis. |
| `TypeSafeIntentClassifier` | **None** (now wrapped) | Called only when its breaker allows it. GenAI telemetry is still recorded for real calls, and none is recorded while the breaker is OPEN. |
| `GeminiAiModelClient` | **None** (now wrapped) | Called only when its breaker allows it. Its error-text replies (*"Unable to get response from AI Model…"*) no longer reach the shopper and become a 503 instead. |
| `CircuitBreakingIntentClassifier` 🟩 | New `@Primary IntentClassifier` | Records TypeSafe outcomes. Returns `IntentClassification.degraded(...)` when TypeSafe fails or the breaker is OPEN. |
| `CircuitBreakingModelClient` 🟩 | New `@Primary AssistantModelClient` | Records Gemini outcomes. Throws `AssistantUnavailableException` when Gemini fails or the breaker is OPEN. |
| `DefaultIntentResolver` 🟨 | Honours `isDegraded()` | Skips the confidence threshold (degraded confidence is 0.0) and carries `degradedNotice` on `ResolvedIntent`. |
| `IntentClassification` / `ResolvedIntent` 🟨 | New `degradedNotice` component | Additive. Old constructors are kept, and the field is `@JsonIgnore` on the classification. |
| `AssistantChatService` 🟨 | Prefix and new outcomes | Puts the notice in front of the reply for degraded turns. Turn outcomes `degraded` and `unavailable` on `polaris.assistant.turns`. |
| `AssistantUnavailableExceptionHandler` 🟩 | New advice (`HIGHEST_PRECEDENCE`) | Returns 503 Problem Details with `Retry-After`. Documented on the OpenAPI `/chat` operation. |
| Readiness probe 🟨 | `gemini`, `typeSafe` removed from group | An outage keeps the pod in load balancing so shoppers get the messages above instead of a gateway error. Both indicators still appear in `/actuator/health`. |

Tests covering these flows: `CircuitBreakingIntentClassifierTest`, `CircuitBreakingModelClientTest`,
`DefaultIntentResolverTest`, `AssistantChatServiceTest` (outcome metrics), `AssistantChatControllerTest`, and
the end-to-end `AssistantCircuitBreakerIntegrationTest` (WireMock TypeSafe and Gemini through `POST /chat`).
