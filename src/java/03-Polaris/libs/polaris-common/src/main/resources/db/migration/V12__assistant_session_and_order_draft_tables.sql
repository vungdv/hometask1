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
