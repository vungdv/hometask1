package vn.danang.polaris.assistant.dto;

import java.time.Instant;

import vn.danang.polaris.assistant.entity.AssistantMessage;

public record AssistantMessageResponse(
    Long id,
    String role,
    String content,
    String widgetType,
    String widgetPayload,
    String toolCallId,
    Instant createdAt
) {
    public static AssistantMessageResponse from(AssistantMessage message) {
        if (message == null) return null;
        return new AssistantMessageResponse(
            message.getId(),
            message.getRole() != null ? message.getRole().name() : null,
            message.getContent(),
            message.getWidgetType(),
            message.getWidgetPayload(),
            message.getToolCallId(),
            message.getCreatedAt()
        );
    }
}
