package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantOrderDraft;

public record AssistantDraftResponse(
    String id,
    String sessionId,
    Long customerId,
    String status,
    List<DraftItemDto> items,
    BigDecimal totalAmount,
    Instant expiresAt,
    String confirmedOrderNumber,
    Instant createdAt,
    Instant updatedAt
) {
    public static AssistantDraftResponse from(AssistantOrderDraft draft) {
        if (draft == null) return null;
        return new AssistantDraftResponse(
            draft.getId(),
            draft.getSession() != null ? draft.getSession().getId() : null,
            draft.getCustomerId(),
            draft.getStatus() != null ? draft.getStatus().name() : null,
            draft.getItems(),
            draft.getTotalAmount(),
            draft.getExpiresAt(),
            draft.getConfirmedOrderNumber(),
            draft.getCreatedAt(),
            draft.getUpdatedAt()
        );
    }
}
