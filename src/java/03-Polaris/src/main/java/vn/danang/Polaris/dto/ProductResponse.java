package vn.danang.polaris.dto;

import java.math.BigDecimal;
import java.time.Instant;

import vn.danang.polaris.entity.Product;

public record ProductResponse(
    Long id,
    String sku,
    String name,
    String description,
    String category,
    BigDecimal price,
    Integer stockQuantity,
    Boolean isAvailable,
    Boolean active,
    Instant createdAt
) {
    public static ProductResponse from(Product product) {
        boolean available = product.getStockQty() != null 
                && product.getStockQty() > 0 
                && Boolean.TRUE.equals(product.getIsActive());

        return new ProductResponse(
            product.getId(),
            product.getSku(),
            product.getName(),
            product.getDescription(),
            product.getCategory(),
            product.getPrice(),
            product.getStockQty(),
            available,
            product.getIsActive(),
            Instant.now()
        );
    }
}