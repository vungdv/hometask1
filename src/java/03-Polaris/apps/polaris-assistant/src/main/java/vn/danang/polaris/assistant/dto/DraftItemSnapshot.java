package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;

/** One priced, quantity-snapshotted line item inside an AssistantOrderDraft.items TEXT column. */
public record DraftItemSnapshot(String sku, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
