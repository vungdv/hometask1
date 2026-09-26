package vn.danang.polaris.assistant.dto;

/**
 * One requested line item (SKU + quantity) for {@code stage_order_draft}.
 *
 * <p>Deliberately a local mirror of {@code apps/polaris}'s {@code order.dto.OrderItemRequest}
 * shape, not an import of that class: REST/MCP is the published contract between the Order and
 * Assistant contexts, not the DTO class file. Adding a compile-time dependency from
 * {@code apps/polaris-assistant} onto {@code apps/polaris}'s JAR just to reuse a two-field record
 * would itself be a Principle 2.2 (bounded-context) violation.
 */
public record OrderItemRequest(String sku, Integer quantity) {
}
