package vn.danang.polaris.assistant.tools;

import java.math.BigDecimal;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed client for Catalog's published, stable {@code GET /api/v1/products/sku/{sku}} contract.
 * Read-only by design: this is the only way {@code stage_order_draft} (WO-020) verifies stock —
 * it never calls {@code OrderService} or any Order-context internal.
 */
public interface CatalogRestClient {

    /**
     * One product's read-only view as reported by Catalog. Empty when the SKU doesn't exist (404).
     * Field names match {@code ProductResponse}'s JSON shape; unrelated fields (id, description,
     * category, ...) are ignored rather than mirrored across the module boundary.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogProductView(String sku, String name, BigDecimal price, Integer stockQuantity, Boolean isAvailable) {
    }

    Optional<CatalogProductView> getBySku(String sku);
}
