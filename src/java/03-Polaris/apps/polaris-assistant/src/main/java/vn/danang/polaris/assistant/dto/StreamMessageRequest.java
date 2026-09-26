package vn.danang.polaris.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the SSE streaming turn endpoint (ADR-0004 §1.A). {@code sessionId} is a path
 * variable, not a body field here — unlike {@link ChatMessageRequest}, whose {@code message} name
 * this deliberately does not reuse.
 */
@Schema(description = "Request payload to send a streamed chat message to the assistant")
public record StreamMessageRequest(
        @NotBlank(message = "Message content must not be blank.")
        @Schema(description = "User natural language message", example = "order 2 of NG-EARBUD-01")
        String content
) {
}
