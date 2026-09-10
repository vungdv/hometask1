package vn.danang.polaris.assistant.tool;

import java.util.Map;

import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.service.ProductService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Component
public class GetProductStockTool implements AssistantTool {

    private final ProductService productService;

    public GetProductStockTool(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
            "get_product_stock",
            "Check live stock and product details for a given SKU",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "sku", Map.of("type", "string", "description", "SKU code of the product (e.g. NG-EARBUD-01)")
                ),
                "required", java.util.List.of("sku")
            )
        );
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, SessionContext context) {
        String sku = arguments.get("sku") != null ? arguments.get("sku").toString().trim() : null;
        if (sku == null || sku.isBlank()) {
            return ToolExecutionResult.failure("get_product_stock", "SKU parameter is required.");
        }

        try {
            ProductResponse product = productService.getProductBySku(sku);
            return ToolExecutionResult.successWithWidget(
                    "get_product_stock",
                    product,
                    "PRODUCT_CARD",
                    product
            );
        } catch (ResourceNotFoundException e) {
            return ToolExecutionResult.failure("get_product_stock", e.getMessage());
        }
    }
}
