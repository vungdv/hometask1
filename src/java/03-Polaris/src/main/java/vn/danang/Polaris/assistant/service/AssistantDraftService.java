package vn.danang.polaris.assistant.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.assistant.dto.DraftItemDto;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.repository.AssistantOrderDraftRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Service
@Transactional
public class AssistantDraftService {

    private static final int DEFAULT_TTL_MINUTES = 15;

    private final AssistantOrderDraftRepository draftRepository;
    private final AssistantSessionRepository sessionRepository;

    public AssistantDraftService(
            AssistantOrderDraftRepository draftRepository,
            AssistantSessionRepository sessionRepository) {
        this.draftRepository = draftRepository;
        this.sessionRepository = sessionRepository;
    }

    public AssistantOrderDraft stageDraft(String sessionId, Long customerId, List<DraftItemDto> items) {
        return stageDraft(sessionId, customerId, items, null, DEFAULT_TTL_MINUTES);
    }

    public AssistantOrderDraft stageDraft(
            String sessionId,
            Long customerId,
            List<DraftItemDto> items,
            BigDecimal totalAmount,
            Integer ttlMinutes) {
        AssistantSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Assistant session not found with id: " + sessionId));

        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new IllegalStateException("Cannot stage draft in a CLOSED session");
        }

        BigDecimal computedTotal = totalAmount;
        if (computedTotal == null) {
            computedTotal = (items != null) ? items.stream()
                    .map(item -> {
                        if (item.subtotal() != null) {
                            return item.subtotal();
                        }
                        if (item.unitPrice() != null && item.quantity() != null) {
                            return item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
                        }
                        return BigDecimal.ZERO;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add) : BigDecimal.ZERO;
        }

        int ttl = (ttlMinutes != null && ttlMinutes > 0) ? ttlMinutes : DEFAULT_TTL_MINUTES;
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(ttl));

        // Supersede any existing WAITING_CONFIRMATION draft for this session
        draftRepository.findBySessionIdAndStatus(sessionId, DraftStatus.WAITING_CONFIRMATION)
                .ifPresent(existing -> {
                    existing.setStatus(DraftStatus.CANCELLED);
                    existing.setUpdatedAt(Instant.now());
                    draftRepository.save(existing);
                });

        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId(UUID.randomUUID().toString());
        draft.setSession(session);
        draft.setCustomerId(customerId);
        draft.setStatus(DraftStatus.WAITING_CONFIRMATION);
        draft.setItems(items != null ? new ArrayList<>(items) : new ArrayList<>());
        draft.setTotalAmount(computedTotal);
        draft.setExpiresAt(expiresAt);
        draft.setCreatedAt(Instant.now());
        draft.setUpdatedAt(Instant.now());
        draft.setVersion(0L);

        return draftRepository.save(draft);
    }

    public AssistantOrderDraft getDraft(String draftId) {
        AssistantOrderDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("Order draft not found with id: " + draftId));

        if (draft.getStatus() == DraftStatus.WAITING_CONFIRMATION && draft.isExpired()) {
            draft.setStatus(DraftStatus.EXPIRED);
            draft.setUpdatedAt(Instant.now());
            draft = draftRepository.save(draft);
        }

        return draft;
    }

    public Optional<AssistantOrderDraft> getActiveDraft(String sessionId) {
        Optional<AssistantOrderDraft> draftOpt = draftRepository.findBySessionIdAndStatus(sessionId, DraftStatus.WAITING_CONFIRMATION);
        if (draftOpt.isPresent()) {
            AssistantOrderDraft draft = draftOpt.get();
            if (draft.isExpired()) {
                draft.setStatus(DraftStatus.EXPIRED);
                draft.setUpdatedAt(Instant.now());
                draftRepository.save(draft);
                return Optional.empty();
            }
            return Optional.of(draft);
        }
        return Optional.empty();
    }

    public int expireDrafts() {
        List<AssistantOrderDraft> expired = draftRepository.findExpiredDrafts(Instant.now());
        if (expired.isEmpty()) {
            return 0;
        }
        for (AssistantOrderDraft d : expired) {
            d.setStatus(DraftStatus.EXPIRED);
            d.setUpdatedAt(Instant.now());
        }
        draftRepository.saveAll(expired);
        return expired.size();
    }
}
