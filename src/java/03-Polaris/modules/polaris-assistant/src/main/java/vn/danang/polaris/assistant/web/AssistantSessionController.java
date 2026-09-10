package vn.danang.polaris.assistant.web;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.danang.polaris.assistant.dto.AssistantSessionDetailResponse;
import vn.danang.polaris.assistant.dto.AssistantSessionResponse;
import vn.danang.polaris.assistant.dto.CreateAssistantSessionRequest;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.assistant.service.AssistantSessionService;

@RestController
@RequestMapping("/api/v1/assistant/sessions")
@Tag(name = "Assistant", description = "AI Assistant conversational session and draft management")
public class AssistantSessionController {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final AssistantSessionService sessionService;
    private final AssistantDraftService draftService;
    private final AssistantMessageRepository messageRepository;

    public AssistantSessionController(
            AssistantSessionService sessionService,
            AssistantDraftService draftService,
            AssistantMessageRepository messageRepository) {
        this.sessionService = sessionService;
        this.draftService = draftService;
        this.messageRepository = messageRepository;
    }

    @PostMapping
    @Operation(summary = "Create assistant session", description = "Initialize a new conversational assistant session for the authenticated user.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Session successfully created"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - Bearer token missing or invalid")
    })
    public ResponseEntity<AssistantSessionResponse> createSession(
            @RequestBody(required = false) CreateAssistantSessionRequest request,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {

        String userId = resolveUserId(request, jwt, authentication);
        Long customerId = (request != null) ? request.customerId() : null;

        AssistantSession session = sessionService.createSession(userId, customerId);
        URI location = URI.create("/api/v1/assistant/sessions/" + session.getId());

        return ResponseEntity.created(location).body(AssistantSessionResponse.from(session));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get assistant session details", description = "Retrieve session state, message history, and active order draft.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Session details retrieved"),
        @ApiResponse(responseCode = "400", description = "Invalid session ID format"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - Bearer token missing or invalid"),
        @ApiResponse(responseCode = "404", description = "Session not found")
    })
    public ResponseEntity<AssistantSessionDetailResponse> getSession(@PathVariable String sessionId) {
        validateSessionId(sessionId);

        AssistantSession session = sessionService.getSession(sessionId);
        List<AssistantMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        AssistantOrderDraft activeDraft = draftService.getActiveDraft(sessionId).orElse(null);

        return ResponseEntity.ok(AssistantSessionDetailResponse.from(session, messages, activeDraft));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Close assistant session", description = "Transitions the specified session to CLOSED status.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Session successfully closed"),
        @ApiResponse(responseCode = "400", description = "Invalid session ID format"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - Bearer token missing or invalid"),
        @ApiResponse(responseCode = "404", description = "Session not found")
    })
    public ResponseEntity<Void> closeSession(@PathVariable String sessionId) {
        validateSessionId(sessionId);

        sessionService.closeSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session ID: must be 1 to 64 alphanumeric characters, underscores, or hyphens.");
        }
    }

    private String resolveUserId(CreateAssistantSessionRequest request, Jwt jwt, Authentication authentication) {
        if (request != null && request.userId() != null && !request.userId().isBlank()) {
            return request.userId();
        }
        if (jwt != null) {
            String sub = jwt.getSubject();
            if (sub != null && !sub.isBlank()) {
                return sub;
            }
            String username = jwt.getClaimAsString("preferred_username");
            if (username != null && !username.isBlank()) {
                return username;
            }
        }
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName();
        }
        return "user";
    }
}
