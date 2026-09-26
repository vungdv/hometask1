package vn.danang.polaris.assistant.entity;

/**
 * Lifecycle states of an {@link AssistantSession}, matching the state machine in
 * ADR-0004 §3.A (Assistant Session State Machine).
 */
public enum AssistantSessionStatus {
    ACTIVE,
    WAITING_CONFIRMATION,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    CLOSED
}
