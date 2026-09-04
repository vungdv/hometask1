package vn.danang.polaris.dto;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import vn.danang.polaris.entity.Product;

public record ProductResponse(
    UUID id,
    String tenantId,
    String sku,
    String name,
    String description,
    BigDecimal price,
    Integer stockQuantity,
    Boolean active,
    Instant createdAt,
    Instant updatedAt
) {
    public static ProductResponse getProductDefault(Clock clock) {
        Instant now = Instant.now(clock);
        return new ProductResponse(
            UUID.randomUUID(),
            "",
            "",
            "",
            "A new product",
            BigDecimal.ZERO,
            0,
            false,
            now,
            now
        );
    }

    public static ProductResponse getProduct(Product product) {
    Instant now = Instant.now();
    return new ProductResponse(
        null,
        "",
        product.getSku(),
        product.getName(),
        product.getName(),
        product.getPrice(),
        product.getStockQty(),
        true,
        now,
        now
        );
    }
}