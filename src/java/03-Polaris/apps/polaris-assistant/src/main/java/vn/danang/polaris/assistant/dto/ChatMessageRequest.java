package vn.danang.polaris.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request payload to send a chat message to the assistant")
public record ChatMessageRequest(
    @Schema(description = "Optional session identifier. If omitted, a session is automatically generated.", example = "123e4567-e89b-12d3-a456-426614174000")
    String sessionId,

    @Schema(description = "User natural language message", example = "Can you tell me about your shipping policy?")
    String message,

    @Schema(description = "Alternative message field name for compatibility", example = "Can you tell me about your shipping policy?")
    String content
) {
    public ChatMessageRequest(String message) {
        this(null, message, null);
    }

    public ChatMessageRequest(String sessionId, String message) {
        this(sessionId, message, null);
    }

    public String resolvedMessage() {
        if (message != null && !message.isBlank()) {
            return message.trim();
        }
        if (content != null && !content.isBlank()) {
            return content.trim();
        }
        return "";
    }
}
