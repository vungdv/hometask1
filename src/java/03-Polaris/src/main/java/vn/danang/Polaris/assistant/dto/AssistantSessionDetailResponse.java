package vn.danang.polaris.assistant.dto;

import java.time.Instant;
import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantSession;

public record AssistantSessionDetailResponse(
    String id,
    String userId,
    Long customerId,
    String status,
    List<AssistantMessageResponse> messages,
    AssistantDraftResponse activeDraft,
    Instant createdAt,
    Instant updatedAt
) {
    public static AssistantSessionDetailResponse from(
            AssistantSession session,
            List<AssistantMessage> messages,
            AssistantOrderDraft activeDraft) {
        if (session == null) return null;
        return new AssistantSessionDetailResponse(
            session.getId(),
            session.getUserId(),
            session.getCustomerId(),
            session.getStatus() != null ? session.getStatus().name() : null,
            messages != null ? messages.stream().map(AssistantMessageResponse::from).toList() : List.of(),
            AssistantDraftResponse.from(activeDraft),
            session.getCreatedAt(),
            session.getUpdatedAt()
        );
    }
}
