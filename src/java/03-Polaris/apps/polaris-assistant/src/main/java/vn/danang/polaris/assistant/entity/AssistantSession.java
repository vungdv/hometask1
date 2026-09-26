package vn.danang.polaris.assistant.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A shopper's conversational session with the AI assistant. Its lifecycle (ADR-0004 §3.A)
 * is driven entirely by WO-020 (staging) and WO-021 (confirm/cancel/expire) — this entity
 * is pure persistence, no transition logic.
 *
 * <p>{@code id} is assigned by the caller (format {@code "sess-" + UUID}), not generated,
 * so it is never annotated with {@code @GeneratedValue}.
 */
@Entity
@Table(name = "assistant_sessions")
@Getter
@Setter
public class AssistantSession {

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private AssistantSessionStatus status = AssistantSessionStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
