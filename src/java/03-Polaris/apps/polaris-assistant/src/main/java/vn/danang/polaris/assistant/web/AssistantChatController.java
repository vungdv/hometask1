package vn.danang.polaris.assistant.web;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.service.AssistantChatService;

@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant Chat", description = "AI Assistant chat conversation endpoints")
public class AssistantChatController {

    private final AssistantChatService chatService;

    public AssistantChatController(AssistantChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    @Operation(summary = "Send chat message to AI assistant", description = "Forwards the user message to the AI model and returns the response.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "AI model response received"),
        @ApiResponse(responseCode = "400", description = "Invalid request or blank message")
    })
    public ResponseEntity<ChatMessageResponse> chat(
            @Valid @RequestBody ChatMessageRequest request,
            Principal principal) {
        String userId = (principal != null) ? principal.getName() : "anonymous";
        ChatMessageResponse response = chatService.sendMessage(request, userId);
        return ResponseEntity.ok(response);
    }
}
