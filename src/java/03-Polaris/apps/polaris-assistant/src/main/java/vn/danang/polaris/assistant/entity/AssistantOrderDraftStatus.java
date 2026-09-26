package vn.danang.polaris.assistant.entity;

/**
 * Lifecycle states of an {@link AssistantOrderDraft}, matching the state machine in
 * ADR-0004 §3.B (Order Draft State Machine & Expiration TTL).
 */
public enum AssistantOrderDraftStatus {
    WAITING_CONFIRMATION,
    CONFIRMED,
    EXPIRED,
    CANCELLED
}
