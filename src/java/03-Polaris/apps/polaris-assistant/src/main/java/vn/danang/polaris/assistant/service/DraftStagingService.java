package vn.danang.polaris.assistant.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.dto.DraftItemSnapshot;
import vn.danang.polaris.assistant.dto.OrderItemRequest;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftRepository;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftStatus;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.AssistantSessionRepository;
import vn.danang.polaris.assistant.entity.AssistantSessionStatus;
import vn.danang.polaris.assistant.tools.CatalogRestClient;
import vn.danang.polaris.assistant.tools.CatalogRestClient.CatalogProductView;
import vn.danang.polaris.web.exception.InsufficientStockActions;
import vn.danang.polaris.web.exception.InsufficientStockException;

/**
 * Orchestrates {@code stage_order_draft}: read-only stock verification against Catalog (never
 * {@code OrderService}, never any Order-context internal), and insert-or-update-by-{@code draftId}
 * persistence of an {@link AssistantOrderDraft} (WO-019). Pure Assistant-context orchestration —
 * the one cross-context call is {@link CatalogRestClient#getBySku(String)}.
 */
@Service
public class DraftStagingService {

    /** Session states from which any new inbound turn resumes the session back to ACTIVE (ADR-0004 §3.A). */
    private static final Set<AssistantSessionStatus> RESUMABLE_FROM =
            EnumSet.of(AssistantSessionStatus.CONFIRMED, AssistantSessionStatus.EXPIRED, AssistantSessionStatus.CANCELLED);

    /** Outcome of a staging attempt: either a persisted draft, or a rejection that touched no row. */
    public sealed interface StageOutcome {
        record Staged(AssistantOrderDraft draft, List<DraftItemSnapshot> items) implements StageOutcome {
        }

        record Rejected(String sku, int requested, int available, List<Map<String, Object>> actions) implements StageOutcome {
        }
    }

    private final AssistantSessionRepository sessionRepository;
    private final AssistantOrderDraftRepository draftRepository;
    private final CatalogRestClient catalogRestClient;
    private final ObjectMapper objectMapper;
    private final AssistantDraftProperties draftProperties;

    @Autowired
    public DraftStagingService(
            AssistantSessionRepository sessionRepository,
            AssistantOrderDraftRepository draftRepository,
            CatalogRestClient catalogRestClient,
            ObjectMapper objectMapper,
            AssistantDraftProperties draftProperties) {
        this.sessionRepository = sessionRepository;
        this.draftRepository = draftRepository;
        this.catalogRestClient = catalogRestClient;
        this.objectMapper = objectMapper;
        this.draftProperties = draftProperties;
    }

    /**
     * Stages (or updates, when {@code draftId} is supplied) an order draft.
     *
     * @param sessionId the conversation's session id (same id used across {@code /chat}/SSE) —
     *                  becomes {@link AssistantSession#getId()} verbatim, not re-prefixed
     * @param userId the caller's user id, needed to create a brand-new session row (not part of
     *               this WO's own pseudocode signature, added because {@code assistant_sessions.user_id}
     *               is {@code NOT NULL} and nothing else in scope supplies it)
     * @param customerId the customer this draft is placed for
     * @param draftId existing draft id to update in place, or {@code null} to start a new draft
     * @param items the requested line items
     */
    @Transactional
    public StageOutcome stage(String sessionId, String userId, Long customerId, @Nullable String draftId,
            List<OrderItemRequest> items) {
        resolveSession(sessionId, userId, customerId);

        List<DraftItemSnapshot> snapshots = new ArrayList<>();
        for (OrderItemRequest item : items) {
            Optional<CatalogProductView> product = catalogRestClient.getBySku(item.sku());
            int available = product.map(CatalogProductView::stockQuantity).filter(q -> q != null).orElse(0);
            int requested = item.quantity();

            if (requested > available) {
                InsufficientStockException ex = new InsufficientStockException(item.sku(), requested, available);
                return new StageOutcome.Rejected(item.sku(), requested, available, InsufficientStockActions.build(ex));
            }

            BigDecimal unitPrice = product.get().price();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(requested));
            snapshots.add(new DraftItemSnapshot(item.sku(), requested, unitPrice, lineTotal));
        }

        BigDecimal totalAmount = snapshots.stream()
                .map(DraftItemSnapshot::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(draftProperties.getTtl());

        AssistantOrderDraft draft = (draftId != null)
                ? draftRepository.findByIdAndSessionId(draftId, sessionId).orElse(null)
                : null;
        boolean isNewDraft = draft == null;
        if (isNewDraft) {
            draft = new AssistantOrderDraft();
            draft.setId("dft-" + UUID.randomUUID());
            draft.setSessionId(sessionId);
            draft.setCustomerId(customerId);
        }
        draft.setStatus(AssistantOrderDraftStatus.WAITING_CONFIRMATION);
        draft.setItemsJson(serialize(snapshots));
        draft.setTotalAmount(totalAmount);
        draft.setExpiresAt(expiresAt);
        draft.setUpdatedAt(now);
        draft = draftRepository.save(draft);

        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(AssistantSessionStatus.WAITING_CONFIRMATION);
            session.setUpdatedAt(now);
            sessionRepository.save(session);
        });

        return new StageOutcome.Staged(draft, snapshots);
    }

    /** Upsert-by-sessionId, resuming a terminal session back to ACTIVE for a new inbound turn. */
    private void resolveSession(String sessionId, String userId, @Nullable Long customerId) {
        Optional<AssistantSession> existing = sessionRepository.findById(sessionId);
        if (existing.isEmpty()) {
            AssistantSession session = new AssistantSession();
            session.setId(sessionId);
            session.setUserId(userId);
            session.setCustomerId(customerId);
            session.setStatus(AssistantSessionStatus.ACTIVE);
            sessionRepository.save(session);
            return;
        }

        AssistantSession session = existing.get();
        if (RESUMABLE_FROM.contains(session.getStatus())) {
            session.setStatus(AssistantSessionStatus.ACTIVE);
            session.setUpdatedAt(Instant.now());
            sessionRepository.save(session);
        }
    }

    private String serialize(List<DraftItemSnapshot> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize draft items", e);
        }
    }
}
