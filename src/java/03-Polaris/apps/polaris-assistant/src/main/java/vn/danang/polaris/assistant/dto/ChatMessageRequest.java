package vn.danang.polaris.assistant.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request payload to send a chat message to the assistant")
public record ChatMessageRequest(

        @Schema(
                description = "Optional session identifier. If omitted, a session is automatically generated.",
                example = "123e4567-e89b-12d3-a456-426614174000"
        )
        String sessionId,

        @Schema(
                description = "User natural language message",
                example = "Update status of the order ORD-1001?"
        )
        String message

) {

    public ChatMessageRequest(String message) {
        this(null, message);
    }

    public ChatMessageRequest {
        // Generate the session ID once during construction.
        sessionId = sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString()
                : sessionId;
    }

    public String resolvedMessage() {
        if (message != null && !message.isBlank()) {
            return message.trim();
        }
        return "";
    }
}