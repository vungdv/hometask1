package vn.danang.polaris.assistant.dto;

import java.time.Instant;

import vn.danang.polaris.assistant.entity.AssistantSession;

public record AssistantSessionResponse(
    String id,
    String userId,
    Long customerId,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
    public static AssistantSessionResponse from(AssistantSession session) {
        if (session == null) return null;
        return new AssistantSessionResponse(
            session.getId(),
            session.getUserId(),
            session.getCustomerId(),
            session.getStatus() != null ? session.getStatus().name() : null,
            session.getCreatedAt(),
            session.getUpdatedAt()
        );
    }
}
