# Slice Work Order: WO-019
## Title: Assistant Session & Order Draft Persistence Schema

- **Target Modules:** `libs/polaris-common` (Flyway migration only), `apps/polaris-assistant`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0004](../decisions/0004-web-chat-ai-assistant-architecture.md) §3 (Session & Order Draft Persistence State Machines)
- **Product Reference:** [PRD-003](../../business/prds/PRD-003-web-chat-ai-assistant.md) §3.5 / Scenario 5
- **Event Model Reference:** [EM-001](../event-models/EM-001-order-staging-out-of-stock-exception.md) — this WO is the first of four (WO-019..WO-022) closing the Gap Analysis in EM-001 §3
- **Bounded Context:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Cross-Context Contracts Consumed:** none. This slice is pure schema + entity + repository plumbing, entirely internal to the Assistant context. No controller, no orchestration logic.
- **Status:** PROPOSED

---

## 1. Objective & Scope

[EM-001](../event-models/EM-001-order-staging-out-of-stock-exception.md) §3 records that `assistant_order_drafts` and `assistant_sessions` were never created — only `assistant_messages` exists, and even that has no repository writing to it today. This WO builds the durable storage ADR-0004 §3.C specifies, so WO-020 (staging orchestration) and WO-021 (confirm/cancel) have somewhere to persist state. It does **not** wire any service/controller logic — that is WO-020/WO-021's job. Vertical completeness for this slice means: migration → entity → repository → a repository-level test proving the schema and JPA mapping agree, nothing higher.

**Constraint Checklist:**
- [ ] Does not edit `V6__assistant_session_draft_schema.sql` (see Task 1 rationale — that migration is already applied wherever the schema has bootstrapped once; editing an applied Flyway migration breaks checksum validation for every environment that already ran it, per [AGENTS.md Principle 1.4](../../../AGENTS.md)).
- [ ] New tables use `TEXT` for the drafts' serialized item list, not `JSONB` — same accommodation the original `assistant_messages.widget_payload` column already made, to keep the local H2 dev profile (`apps/polaris-assistant/src/main/resources/application.yml`) and the Postgres docker profile schema-compatible (AGENTS.md 1.4 "Dev-Prod Parity"). ADR-0004 §3.C shows `JSONB`; this WO deliberately deviates for cross-dialect safety and says so instead of silently drifting from the ADR.
- [ ] `assistant_messages` is left untouched. It has no `session_id` FK and no repository wiring anything to it today; retrofitting message persistence is a pre-existing gap outside EM-001's scope and is **not** bundled into this slice (Principle 2 "Minimal Feasible Diff").
- [ ] 100% unit/repository test pass (`mvn clean test -pl apps/polaris-assistant`), Flyway migrates cleanly on a fresh schema.

---

## 2. Detailed Technical Tasks

### Task 1: New Flyway Migration (do not touch V6)
**File (new):** `libs/polaris-common/src/main/resources/db/migration/V12__assistant_session_and_order_draft_tables.sql`

`V11__add_customer_version_for_optimistic_locking.sql` is the current head; both `apps/polaris` and `apps/polaris-assistant` load Flyway against the *same* physical schema in the docker profile (`polaris-db:5432/polaris` — see `docker-compose.yml` `polaris-assistant.environment.SPRING_DATASOURCE_URL`), and against the same migration path on H2 in the local profile. `V6` already ran (checksummed) in both, with `assistant_sessions`/`assistant_order_drafts` commented out. A new, additive migration is the only forward-only option:

```sql
-- Completes the schema ADR-0004 §3.C specifies for assistant_sessions and
-- assistant_order_drafts, without altering the already-applied V6 migration.
-- Uses TEXT (not JSONB) for the drafts' serialized item snapshot: same
-- cross-dialect accommodation V6 already made for assistant_messages.widget_payload,
-- so the H2 (local) and PostgreSQL (docker) profiles stay schema-compatible.

CREATE TABLE assistant_sessions (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    customer_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_assistant_sessions_user ON assistant_sessions(user_id, status);

CREATE TABLE assistant_order_drafts (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES assistant_sessions(id) ON DELETE CASCADE,
    customer_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING_CONFIRMATION',
    items TEXT NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_order_number VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_assistant_drafts_session ON assistant_order_drafts(session_id, status);
CREATE INDEX idx_assistant_drafts_expiry ON assistant_order_drafts(status, expires_at);
```

### Task 2: `AssistantSession` Entity
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/entity/AssistantSession.java`

JPA entity mapping 1:1 to `assistant_sessions`: `id` (String, PK, assigned — not generated, format `"sess-" + UUID`), `userId`, `customerId` (nullable `Long`), `status` (`@Enumerated(STRING)`, see Task 4), `createdAt`/`updatedAt` (`Instant`), `version` (`@Version Long`, optimistic locking per ADR-0004 §3.B).

### Task 3: `AssistantOrderDraft` Entity
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/entity/AssistantOrderDraft.java`

Maps 1:1 to `assistant_order_drafts`: `id` (String PK, format `"dft-" + UUID`), `sessionId`, `customerId`, `status` (`@Enumerated(STRING)`, see Task 4), `itemsJson` (`@Column(name="items", columnDefinition="TEXT")` — a Jackson-serialized `List<DraftItemSnapshot>`, see Task 5), `totalAmount` (`BigDecimal`, `precision=12, scale=2`), `expiresAt` (`Instant`), `confirmedOrderNumber` (nullable `String`), `createdAt`/`updatedAt`, `version` (`@Version Long`).

Do **not** map `sessionId` as a `@ManyToOne` JPA association to `AssistantSession` — keep it a plain FK-backed `String` column. WO-020/WO-021 look up sessions and drafts independently by primary key; an eager/lazy association here would invite exactly the kind of hidden cross-aggregate coupling AGENTS.md 3.3 (anti-god-class / single responsibility) warns against for a table that's really two independent aggregates sharing a foreign key.

### Task 4: Status Enums
**Files (new):**
- `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/entity/AssistantSessionStatus.java` — `ACTIVE, WAITING_CONFIRMATION, CONFIRMED, EXPIRED, CANCELLED, CLOSED` (exact match to the state machine in [ADR-0004 §3.A](../decisions/0004-web-chat-ai-assistant-architecture.md#a-assistant-session-state-machine)).
- `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/entity/AssistantOrderDraftStatus.java` — `WAITING_CONFIRMATION, CONFIRMED, EXPIRED, CANCELLED` (exact match to [ADR-0004 §3.B](../decisions/0004-web-chat-ai-assistant-architecture.md#b-order-draft-state-machine--expiration-ttl)).

Transition logic (which WO moves a draft/session from one status to another) is intentionally **not** implemented here — see WO-020 (staging → `WAITING_CONFIRMATION`) and WO-021 (confirm/cancel/expire).

### Task 5: `DraftItemSnapshot` Wire/Storage DTO
**File (new):** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/dto/DraftItemSnapshot.java`

```java
package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;

/** One priced, quantity-snapshotted line item inside an AssistantOrderDraft.items TEXT column. */
public record DraftItemSnapshot(String sku, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
```

This is the **single canonical shape** for a draft line item: it is serialized into `assistant_order_drafts.items` (via `ObjectMapper`, this WO), it is what WO-020 builds when staging succeeds, it is what the SSE `draft` event's `items[]` array carries (WO-020), and it is what WO-021's cancel-response reuses. Defining it once here — rather than letting each downstream WO invent its own shape — is what keeps M5 (single source of truth) satisfied across WO-020/WO-021.

### Task 6: Repositories
**Files (new):**
- `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/entity/AssistantSessionRepository.java` — `extends JpaRepository<AssistantSession, String>`.
- `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/entity/AssistantOrderDraftRepository.java` — `extends JpaRepository<AssistantOrderDraft, String>`, plus:
  - `Optional<AssistantOrderDraft> findByIdAndSessionId(String id, String sessionId)` — the lookup WO-020 (update-by-draftId) and WO-021 (confirm/cancel) both need, scoped defensively to the owning session so one session can never touch another's draft by guessing an id.
  - `List<AssistantOrderDraft> findBySessionIdAndStatus(String sessionId, AssistantOrderDraftStatus status)`.
  - `int updateStatusExpiredWhereWaitingAndPastTtl(Instant now)` (a `@Modifying @Query` bulk update: `UPDATE AssistantOrderDraft d SET d.status = 'EXPIRED' WHERE d.status = 'WAITING_CONFIRMATION' AND d.expiresAt < :now`) — the primitive WO-021's scheduled TTL sweep calls; defining the query here (next to the entity/index it depends on) rather than in WO-021 keeps the index (`idx_assistant_drafts_expiry`) and the query that exploits it next to each other.

---

## 3. Given/When/Then Acceptance Criteria

1. **Given** migrations `V1..V11` already applied, **when** `V12__assistant_session_and_order_draft_tables.sql` runs, **then** `assistant_sessions` and `assistant_order_drafts` exist with the exact columns/indexes above, Flyway reports no checksum mismatch on `V6`, and `assistant_messages` is unchanged.
2. **Given** a fresh `AssistantSession`, **when** saved via `AssistantSessionRepository.save`, **then** the row persists with `status=ACTIVE` and `version=0`.
3. **Given** a valid `session_id`, **when** an `AssistantOrderDraft` referencing it is saved, **then** the row persists; **given** a `session_id` with no matching `assistant_sessions` row, **when** saved, **then** the FK constraint rejects it (proves the migration's `REFERENCES ... ON DELETE CASCADE` is wired, not just declared).
4. **Given** a session with two drafts, one `WAITING_CONFIRMATION` and one `CONFIRMED`, **when** `findBySessionIdAndStatus(sessionId, WAITING_CONFIRMATION)` runs, **then** only the first is returned.
5. **Given** a `WAITING_CONFIRMATION` draft with `expires_at` in the past, **when** `updateStatusExpiredWhereWaitingAndPastTtl(now)` runs, **then** exactly that row transitions to `EXPIRED` and its `version` increments.

---

## 4. Verification & Acceptance Criteria

```bash
mvn clean test -pl libs/polaris-common,apps/polaris-assistant
mvn clean test   # full reactor — confirms apps/polaris's own Flyway history is unaffected
```
Inspect `apps/polaris-assistant/src/test/resources/application.yml` (`spring.jpa.hibernate.ddl-auto: validate`) — this test profile only passes if the new entities match the new migration's DDL exactly, so a green `AssistantOrderDraftRepositoryTest`/`AssistantSessionRepositoryTest` run is itself proof the schema and entities agree.
