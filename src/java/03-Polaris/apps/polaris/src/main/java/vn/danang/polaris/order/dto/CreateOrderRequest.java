package vn.danang.polaris.order.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Order placement request payload")
public record CreateOrderRequest(
    @Schema(description = "Customer database ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Customer ID is required")
    Long customerId,

    @Schema(description = "List of products and quantities to purchase", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "Order must contain at least one item")
    @Valid
    List<OrderItemRequest> items,

    @Schema(description = "Optional idempotency key to prevent duplicate orders during retries", example = "unique-key-123", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    String idempotencyKey
) {}
