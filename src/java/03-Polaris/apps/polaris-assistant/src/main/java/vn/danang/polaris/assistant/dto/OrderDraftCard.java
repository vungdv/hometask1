package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.OrderDraft;

/**
 * Payload of the {@value ChatWidget#ORDER_DRAFT} card (BPMN {@code A_Draft}): exactly the snapshot the
 * shopper confirms by clicking "Submit Order" on this {@code draftId}.
 */
@Schema(description = "ORDER_DRAFT card: staged order awaiting the shopper's confirmation")
public record OrderDraftCard(
        @Schema(description = "Draft to confirm or cancel", example = "dft-3f2a...")
        String draftId,

        @Schema(description = "Price snapshot per line")
        List<DraftLine> items,

        @Schema(description = "Sum of the line totals", example = "49.80")
        BigDecimal total,

        @Schema(description = "The held price lapses at this instant (staging time + 15 min)")
        Instant expiresAt
) {

    public static OrderDraftCard from(OrderDraft draft) {
        return new OrderDraftCard(draft.getId(), List.copyOf(draft.getItems()), draft.getTotalAmount(), draft.getExpiresAt());
    }

    public ChatWidget toWidget() {
        return new ChatWidget(ChatWidget.ORDER_DRAFT, this);
    }
}
