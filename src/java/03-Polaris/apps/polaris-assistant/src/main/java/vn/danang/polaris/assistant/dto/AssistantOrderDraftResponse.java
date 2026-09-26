package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;

/**
 * The canonical draft summary shape: the cancel endpoint's response body here, and a projection
 * of the same fields (minus {@code sessionId}/{@code confirmedOrderNumber}) is what WO-020's SSE
 * {@code draft} event carries — one shape, not one invented per surface.
 */
@Schema(description = "An assistant order draft's current state")
public record AssistantOrderDraftResponse(
        String draftId,
        String sessionId,
        String status,
        List<DraftItemSnapshot> items,
        BigDecimal totalAmount,
        Instant expiresAt,
        String confirmedOrderNumber
) {
    public static AssistantOrderDraftResponse from(AssistantOrderDraft draft, List<DraftItemSnapshot> items) {
        return new AssistantOrderDraftResponse(
                draft.getId(),
                draft.getSessionId(),
                draft.getStatus().name(),
                items,
                draft.getTotalAmount(),
                draft.getExpiresAt(),
                draft.getConfirmedOrderNumber()
        );
    }
}
