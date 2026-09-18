package vn.danang.polaris.assistant.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request payload to send a chat message to the assistant")
public record ChatMessageRequest(

        @Schema(
                description = "Optional session identifier. If omitted, a session is automatically generated.",
                example = "123e4567-e89b-12d3-a456-426614174000"
        )
        String sessionId,

        @NotBlank(message = "Message content must not be blank.")
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

    public static void validate(ChatMessageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }
        String rawMessage = request.message();
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }
    }

    public void validate() {
        validate(this);
    }

    public String resolvedMessage() {
        if (message != null && !message.isBlank()) {
            return message.trim();
        }
        return "";
    }
}