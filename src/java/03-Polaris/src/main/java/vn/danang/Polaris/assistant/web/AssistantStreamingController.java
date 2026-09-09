package vn.danang.polaris.assistant.web;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import vn.danang.polaris.assistant.dto.AssistantDraftResponse;
import vn.danang.polaris.assistant.dto.AssistantMessageRequest;
import vn.danang.polaris.assistant.engine.AgencyOrchestrator;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.model.ModelEvent;
import vn.danang.polaris.assistant.model.ModelStreamListener;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.dto.OrderResponse;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@RestController
@RequestMapping("/api/v1/assistant/sessions")
@Tag(name = "Assistant Streaming", description = "AI Assistant SSE streaming conversation and draft confirmation")
public class AssistantStreamingController {

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final long SSE_TIMEOUT_MS = 180_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final AgencyOrchestrator agencyOrchestrator;
    private final AssistantDraftService draftService;

    public AssistantStreamingController(
            AgencyOrchestrator agencyOrchestrator,
            AssistantDraftService draftService) {
        this.agencyOrchestrator = agencyOrchestrator;
        this.draftService = draftService;
    }

    @PostMapping(value = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream assistant conversation", description = "Dispatches a user prompt to the cognitive agency orchestrator and streams thought, token, widget, draft, and done SSE events.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SSE event stream established"),
        @ApiResponse(responseCode = "400", description = "Invalid request payload or session ID"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - Bearer token missing or invalid"),
        @ApiResponse(responseCode = "404", description = "Session not found")
    })
    public SseEmitter streamMessage(
            @PathVariable String sessionId,
            @Valid @RequestBody AssistantMessageRequest request) {

        validateId(sessionId, "sessionId");

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean isCompleted = new AtomicBoolean(false);

        emitter.onCompletion(() -> isCompleted.set(true));
        emitter.onTimeout(() -> {
            isCompleted.set(true);
            emitter.complete();
        });
        emitter.onError(ex -> isCompleted.set(true));

        // Start virtual thread keep-alive heartbeat
        Thread.ofVirtual().start(() -> {
            while (!isCompleted.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (isCompleted.get()) break;
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    isCompleted.set(true);
                    break;
                }
            }
        });

        ModelStreamListener streamListener = event -> {
            if (isCompleted.get()) return;
            try {
                switch (event) {
                    case ModelEvent.ThoughtEvent(String thought) -> {
                        emitter.send(SseEmitter.event().name("thought").data(Map.of("thought", thought)));
                    }
                    case ModelEvent.TokenDeltaEvent(String delta) -> {
                        emitter.send(SseEmitter.event().name("token").data(Map.of("delta", delta)));
                    }
                    case ModelEvent.WidgetEvent(String widgetType, Object payload) -> {
                        emitter.send(SseEmitter.event().name("widget").data(Map.of("type", widgetType, "payload", payload)));
                    }
                    case ModelEvent.DraftEvent(var draft) -> {
                        emitter.send(SseEmitter.event().name("draft").data(draft));
                    }
                    case ModelEvent.ErrorEvent(String error, String remedy) -> {
                        Map<String, Object> problem = new LinkedHashMap<>();
                        problem.put("type", "https://polaris.local/errors/assistant-error");
                        problem.put("title", "Assistant Cognitive Error");
                        problem.put("status", 500);
                        problem.put("detail", error);
                        if (remedy != null) {
                            problem.put("remedy", remedy);
                        }
                        emitter.send(SseEmitter.event().name("error").data(problem));
                    }
                    case ModelEvent.DoneEvent(String finishReason) -> {
                        emitter.send(SseEmitter.event().name("done").data(Map.of(
                                "sessionId", sessionId,
                                "finishReason", finishReason
                        )));
                        isCompleted.set(true);
                        emitter.complete();
                    }
                    default -> {}
                }
            } catch (IOException e) {
                isCompleted.set(true);
                emitter.completeWithError(e);
            }
        };

        agencyOrchestrator.processUserMessageAsync(sessionId, request.content(), streamListener)
                .exceptionally(ex -> {
                    if (!isCompleted.get()) {
                        try {
                            Map<String, Object> problem = new LinkedHashMap<>();
                            problem.put("type", "https://polaris.local/errors/assistant-error");
                            problem.put("title", "Assistant Processing Error");
                            problem.put("status", 500);
                            problem.put("detail", ex.getMessage() != null ? ex.getMessage() : "Inference error");
                            emitter.send(SseEmitter.event().name("error").data(problem));
                            emitter.send(SseEmitter.event().name("done").data(Map.of(
                                    "sessionId", sessionId,
                                    "finishReason", "ERROR"
                            )));
                        } catch (IOException ignored) {}
                        isCompleted.set(true);
                        emitter.complete();
                    }
                    return null;
                });

        return emitter;
    }

    @PostMapping("/{sessionId}/drafts/{draftId}/confirm")
    @Operation(summary = "Confirm assistant order draft", description = "Validates active draft, executes atomic order placement via OrderService, and marks draft as CONFIRMED.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Order created successfully from draft"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - Bearer token missing or invalid"),
        @ApiResponse(responseCode = "404", description = "Session or draft not found"),
        @ApiResponse(responseCode = "409", description = "Draft expired or invalid state")
    })
    public ResponseEntity<OrderResponse> confirmDraft(
            @PathVariable String sessionId,
            @PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        validateId(sessionId, "sessionId");
        validateId(draftId, "draftId");

        Order order = draftService.confirmDraft(sessionId, draftId, idempotencyKey);
        OrderResponse response = OrderResponse.from(order);
        URI location = URI.create("/api/v1/orders/" + order.getOrderNumber());

        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/{sessionId}/drafts/{draftId}/cancel")
    @Operation(summary = "Cancel assistant order draft", description = "Transitions the specified draft from WAITING_CONFIRMATION to CANCELLED.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Draft cancelled successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized - Bearer token missing or invalid"),
        @ApiResponse(responseCode = "404", description = "Session or draft not found")
    })
    public ResponseEntity<AssistantDraftResponse> cancelDraft(
            @PathVariable String sessionId,
            @PathVariable String draftId) {

        validateId(sessionId, "sessionId");
        validateId(draftId, "draftId");

        AssistantOrderDraft cancelled = draftService.cancelDraft(sessionId, draftId);
        return ResponseEntity.ok(AssistantDraftResponse.from(cancelled));
    }

    private void validateId(String id, String fieldName) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid " + fieldName + " format: " + id);
        }
    }
}
