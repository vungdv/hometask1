package vn.danang.polaris.assistant.tool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.service.ProductService;

@Component
public class SearchProductsTool implements AssistantTool {

    private final ProductService productService;

    public SearchProductsTool(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
            "search_products",
            "Search products in the catalog by keyword, category ID, and price range",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "Keyword to search in product title and description"),
                    "categoryId", Map.of("type", "integer", "description", "Optional category ID filter"),
                    "minPrice", Map.of("type", "number", "description", "Optional minimum price"),
                    "maxPrice", Map.of("type", "number", "description", "Optional maximum price")
                )
            )
        );
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, SessionContext context) {
        String query = arguments.get("query") != null ? arguments.get("query").toString() : null;
        Long categoryId = parseLong(arguments.get("categoryId"));
        BigDecimal minPrice = parseBigDecimal(arguments.get("minPrice"));
        BigDecimal maxPrice = parseBigDecimal(arguments.get("maxPrice"));

        Page<ProductResponse> page = productService.searchProducts(
                query, null, categoryId, minPrice, maxPrice, true, PageRequest.of(0, 10));

        List<ProductResponse> products = page.getContent();
        if (products.isEmpty()) {
            return ToolExecutionResult.success("search_products", List.of());
        }

        return ToolExecutionResult.successWithWidget(
                "search_products",
                products,
                "PRODUCT_LIST_CARD",
                products
        );
    }

    private Long parseLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(val.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
