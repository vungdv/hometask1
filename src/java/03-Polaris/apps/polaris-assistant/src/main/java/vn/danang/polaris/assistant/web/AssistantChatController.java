package vn.danang.polaris.assistant.web;

import java.security.Principal;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.service.AssistantChatService;

@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant Chat", description = "AI Assistant chat conversation endpoints")
@SecurityRequirement(name = "keycloak-auth2-codeflow")
public class AssistantChatController {

    private final AssistantChatService chatService;

    public AssistantChatController(AssistantChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    @Operation(summary = "Send chat message to AI assistant", description = "Forwards the user message to the AI model and returns the response.")
    @SecurityRequirement(name = "keycloak-auth2-codeflow")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "AI model response received",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatMessageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request or blank message",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error during chat processing",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ChatMessageResponse> chat(
            @Valid @RequestBody ChatMessageRequest request,
            Principal principal) {
        String userId = (principal != null) ? principal.getName() : "anonymous";
        ChatMessageResponse response = chatService.sendMessage(request, userId);
        return ResponseEntity.ok(response);
    }
}
