package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;

public record DraftItemDto(
    String sku,
    String productName,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal subtotal
) {
    public DraftItemDto {
        if (subtotal == null && unitPrice != null && quantity != null) {
            subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
