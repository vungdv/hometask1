package vn.danang.polaris.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

public record UpdateInventoryRequest(
    @Min(value = 0, message = "Stock quantity cannot be negative")
    @Schema(description = "Absolute stock quantity to set directly", example = "50", minimum = "0")
    Integer quantity,

    @Schema(description = "Relative stock delta adjustment (+/-)", example = "10")
    Integer delta
) {
    public UpdateInventoryRequest(Integer quantity) {
        this(quantity, null);
    }
}
