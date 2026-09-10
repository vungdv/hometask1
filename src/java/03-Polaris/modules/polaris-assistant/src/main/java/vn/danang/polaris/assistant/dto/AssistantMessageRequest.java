package vn.danang.polaris.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Message prompt payload for assistant conversation")
public record AssistantMessageRequest(
    @Schema(description = "User text prompt or message content", example = "Find chargers under $30", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Message content must not be blank")
    String content
) {}
