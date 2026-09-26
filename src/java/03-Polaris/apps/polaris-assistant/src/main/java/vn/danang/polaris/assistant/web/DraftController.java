package vn.danang.polaris.assistant.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.danang.polaris.assistant.dto.AssistantOrderDraftResponse;
import vn.danang.polaris.assistant.service.DraftConfirmationService;
import vn.danang.polaris.assistant.service.DraftConfirmationService.AssistantOrderDraftResult;
import vn.danang.polaris.assistant.service.DraftConfirmationService.ConfirmOutcome;
import vn.danang.polaris.web.exception.InsufficientStockActions;
import vn.danang.polaris.web.exception.InsufficientStockException;

/**
 * Confirm/cancel REST endpoints for a staged order draft (WO-021). A separate controller from
 * {@link AssistantChatController}, per AGENTS.md 3.3 (anti-god-class): the chat controller forwards
 * conversational turns to the ReAct loop, this one is the plain, human-in-the-loop mutation surface
 * ADR-0004 §1.B specifies — the model never calls a "confirm" or "cancel" tool.
 */
@RestController
@RequestMapping("/api/v1/assistant/sessions/{sessionId}/drafts/{draftId}")
@Tag(name = "Assistant Order Drafts", description = "Confirm/cancel a staged order draft")
@SecurityRequirement(name = "keycloak-auth2-codeflow")
public class DraftController {

    private final DraftConfirmationService confirmationService;

    public DraftController(DraftConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping(value = "/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Confirm a staged order draft", description = "Re-verifies live stock, then places the "
            + "order via Order's published POST /api/v1/orders. Never calls OrderService directly.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Order placed; body is Order's own OrderResponse, proxied as-is",
            content = @Content(mediaType = "application/json")),
        @ApiResponse(responseCode = "400", description = "Idempotency-Key header missing, or live stock re-check failed",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Draft not found for this session",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Draft expired, or not in a confirmable state",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Object> confirm(
            @PathVariable String sessionId,
            @PathVariable String draftId,
            @Parameter(description = "Required — this endpoint is the sole gateway a human clicks exactly once")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        // required = false + a manual check (rather than required = true) so this 400s through
        // GlobalExceptionHandler's existing IllegalArgumentException handler - its catch-all
        // Exception handler would otherwise turn Spring's own MissingRequestHeaderException into
        // a bare 500, since @RestControllerAdvice handlers run ahead of Spring MVC's defaults.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required.");
        }

        ConfirmOutcome outcome = confirmationService.confirm(sessionId, draftId, idempotencyKey);

        if (outcome instanceof ConfirmOutcome.Confirmed confirmed) {
            return ResponseEntity
                    .created(URI.create("/api/v1/orders/" + confirmed.orderNumber()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(confirmed.rawOrderResponseBody());
        }

        if (outcome instanceof ConfirmOutcome.StockRejected rejected) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(insufficientStockProblem(rejected.sku(), rejected.requested(), rejected.available()));
        }

        ConfirmOutcome.DownstreamRejected downstream = (ConfirmOutcome.DownstreamRejected) outcome;
        return ResponseEntity.status(downstream.statusCode())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(downstream.rawProblemBody());
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel a staged order draft")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Draft cancelled",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AssistantOrderDraftResponse.class))),
        @ApiResponse(responseCode = "404", description = "Draft not found for this session",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Draft not in a cancellable state",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AssistantOrderDraftResponse> cancel(
            @PathVariable String sessionId,
            @PathVariable String draftId) {
        AssistantOrderDraftResult result = confirmationService.cancel(sessionId, draftId);
        return ResponseEntity.ok(AssistantOrderDraftResponse.from(result.draft(), result.items()));
    }

    /**
     * Mirrors {@code GlobalExceptionHandler.handleInsufficientStockException}'s field set, but
     * calls {@link InsufficientStockActions#build} directly for the full three-action remedy list
     * (WO-020's own pattern) rather than that handler's still-two-action inline list, which only
     * gains {@code remove_item} once WO-022 retrofits it.
     */
    private ProblemDetail insufficientStockProblem(String sku, int requested, int available) {
        InsufficientStockException ex = new InsufficientStockException(sku, requested, available);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Insufficient Stock");
        problem.setType(URI.create("https://polaris.local/errors/out-of-stock"));
        problem.setProperty("sku", sku);
        problem.setProperty("requested_quantity", requested);
        problem.setProperty("available_quantity", available);
        problem.setProperty("remedy", String.format("Reduce order quantity for '%s' to %d or fewer units.", sku, available));
        problem.setProperty("actions", InsufficientStockActions.build(ex));
        return problem;
    }
}
