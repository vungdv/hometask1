package vn.danang.polaris.assistant.dto;

import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing the assistant's reply")
public record ChatMessageResponse(
    @Schema(description = "Session identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    String sessionId,

    @Schema(description = "Message sender role", example = "ASSISTANT")
    String role,

    @Schema(description = "AI Model response message", example = "Hello! I am happy to help you with that.")
    String reply,

    @Schema(description = "Timestamp when message was created")
    Instant createdAt
) {}
