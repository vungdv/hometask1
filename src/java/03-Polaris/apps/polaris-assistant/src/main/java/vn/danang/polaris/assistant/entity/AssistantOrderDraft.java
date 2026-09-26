package vn.danang.polaris.assistant.entity;

import java.math.BigDecimal;
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
 * A staged, priced order draft awaiting shopper confirmation (ADR-0004 §3.B). Owned by exactly
 * one {@link AssistantSession} via {@code session_id}, deliberately mapped as a plain FK-backed
 * column rather than a {@code @ManyToOne} association: WO-020/WO-021 look up sessions and drafts
 * independently by primary key, and this table is really two independent aggregates sharing a
 * foreign key, not a parent/child object graph.
 *
 * <p>{@code id} is assigned by the caller (format {@code "dft-" + UUID}), not generated, so it
 * is never annotated with {@code @GeneratedValue}. {@code itemsJson} holds a Jackson-serialized
 * {@code List<vn.danang.polaris.assistant.dto.DraftItemSnapshot>} — (de)serializing it is the
 * staging orchestration's job (WO-020), not this entity's.
 */
@Entity
@Table(name = "assistant_order_drafts")
@Getter
@Setter
public class AssistantOrderDraft {

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false, updatable = false)
    private String sessionId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private AssistantOrderDraftStatus status = AssistantOrderDraftStatus.WAITING_CONFIRMATION;

    @Column(name = "items", columnDefinition = "TEXT", nullable = false)
    private String itemsJson;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_order_number", length = 64)
    private String confirmedOrderNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
