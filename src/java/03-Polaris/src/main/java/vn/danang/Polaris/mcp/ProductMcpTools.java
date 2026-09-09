package vn.danang.polaris.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.service.ProductService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;
import vn.danang.polaris.web.validator.PageableValidator;

/**
 * Dedicated Presentation Facade exposing catalog and product tools for MCP clients.
 */
@Component
public class ProductMcpTools {

    public static final String TOOL_SEARCH_AVAILABLE_PRODUCTS = "search_available_products";
    public static final String TOOL_GET_PRODUCT_BY_SKU = "get_product_by_sku";

    private static final String SEARCH_PRODUCTS_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Free text search keyword matching product name or description"
            },
            "category": {
              "type": "string",
              "description": "Category name filter (e.g. 'Electronics', 'Footwear')"
            },
            "min_price": {
              "type": "number",
              "description": "Minimum product price filter (e.g. 10.00)"
            },
            "max_price": {
              "type": "number",
              "description": "Maximum product price filter (e.g. 500.00)"
            },
            "available_only": {
              "type": "boolean",
              "description": "Filter for in-stock products only (default: true)"
            },
            "page": {
              "type": "integer",
              "description": "Zero-based page number (default: 0, min: 0, max: 10000)"
            },
            "size": {
              "type": "integer",
              "description": "Page size (default: 20, min: 1, max: 100)"
            },
            "sort": {
              "type": "string",
              "description": "Sort criteria in format 'property,direction' (default: 'id,asc'). Allowed properties: id, sku, name, category, price, stockQuantity, active, createdAt"
            }
          }
        }
        """;

    private static final String GET_PRODUCT_BY_SKU_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "sku": {
              "type": "string",
              "description": "Unique product SKU code (e.g. 'PROD-001' or 'SM-PH-001')"
            }
          },
          "required": ["sku"]
        }
        """;

    private final ProductService productService;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public ProductMcpTools(
            ProductService productService,
            ObjectProvider<Tracer> tracerProvider) {
        this.productService = productService;
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
    }

    public ProductMcpTools(ProductService productService) {
        this(productService, null);
    }

    public McpSchema.Tool getSearchProductsTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_SEARCH_AVAILABLE_PRODUCTS)
                .description("Search catalog for available products matching query, category, and price filters with pagination")
                .inputSchema(jsonMapper, SEARCH_PRODUCTS_SCHEMA)
                .build();
    }

    public McpSchema.Tool getSearchProductsTool() {
        return getSearchProductsTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getProductBySkuTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder()
                .name(TOOL_GET_PRODUCT_BY_SKU)
                .description("Retrieve detailed product specifications and live inventory by SKU code")
                .inputSchema(jsonMapper, GET_PRODUCT_BY_SKU_SCHEMA)
                .build();
    }

    public McpSchema.Tool getProductBySkuTool() {
        return getProductBySkuTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.CallToolResult searchAvailableProducts(Map<String, Object> arguments) {
        try {
            String query = getString(arguments, "query");
            String category = getString(arguments, "category");
            BigDecimal minPrice = getBigDecimal(arguments, "min_price", "minPrice");
            BigDecimal maxPrice = getBigDecimal(arguments, "max_price", "maxPrice");
            Boolean availableOnly = getBoolean(arguments, "available_only", "availableOnly", "available");
            if (availableOnly == null) {
                availableOnly = Boolean.TRUE;
            }

            Integer page = getInteger(arguments, "page");
            Integer size = getInteger(arguments, "size");
            String sort = getString(arguments, "sort");

            int pageNum = page != null ? page : PageableValidator.DEFAULT_PAGE;
            int pageSize = size != null ? size : PageableValidator.DEFAULT_SIZE;
            Sort sortObj = parseSort(sort != null && !sort.isBlank() ? sort : "id,asc");

            Pageable pageable = PageableValidator.validateAndSanitize(PageRequest.of(pageNum, pageSize, sortObj));
            Page<ProductResponse> result = productService.searchProducts(
                    query, category, minPrice, maxPrice, availableOnly, pageable
            );

            String formatted = formatSearchResults(result);
            return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
        } catch (Exception ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error searching products: " + ex.getMessage())
                    .isError(true)
                    .build();
        }
    }

    public McpSchema.CallToolResult getProductBySku(Map<String, Object> arguments) {
        String sku = getString(arguments, "sku");
        if (sku == null || sku.isBlank()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Parameter 'sku' is required.")
                    .isError(true)
                    .build();
        }

        return getProductBySku(sku);
    }

    public McpSchema.CallToolResult getProductBySku(String sku) {
        try {
            ProductResponse product = productService.getProductBySku(sku.trim());
            String formatted = formatProductDetails(product);
            return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
        } catch (ResourceNotFoundException ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Product not found with SKU: " + sku.trim())
                    .isError(true)
                    .build();
        } catch (Exception ex) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error retrieving product '" + sku + "': " + ex.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private String formatSearchResults(Page<ProductResponse> result) {
        if (result == null || result.isEmpty()) {
            return "No products found matching the specified criteria.";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Found " + result.getTotalElements() + " product(s):");
        for (ProductResponse p : result.getContent()) {
            int stock = p.stockQuantity() != null ? p.stockQuantity() : 0;
            String stockStatus = (p.isAvailable() != null && p.isAvailable() && stock > 0)
                    ? stock + " in stock"
                    : "Out of stock";
            String cat = p.category() != null ? p.category() : "General";
            lines.add(String.format(
                    "- [%s] %s | Category: %s | Price: $%s | Availability: %s",
                    p.sku(), p.name(), cat, p.price(), stockStatus
            ));
            if (p.description() != null && !p.description().isBlank()) {
                lines.add("  Description: " + p.description());
            }
        }
        return String.join("\n", lines);
    }

    private String formatProductDetails(ProductResponse product) {
        int stock = product.stockQuantity() != null ? product.stockQuantity() : 0;
        String stockStatus = (product.isAvailable() != null && product.isAvailable() && stock > 0)
                ? String.format("In Stock (%d available)", stock)
                : "OUT OF STOCK";

        String desc = (product.description() != null && !product.description().isBlank())
                ? product.description()
                : "No description provided";

        return String.format(
                "Product Details for %s:\n"
                + "- SKU: %s\n"
                + "- Category: %s\n"
                + "- Price: $%s\n"
                + "- Status: %s\n"
                + "- Description: %s",
                product.name(),
                product.sku(),
                product.category() != null ? product.category() : "General",
                product.price(),
                stockStatus,
                desc
        );
    }

    private Sort parseSort(String sortStr) {
        if (sortStr == null || sortStr.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "id");
        }
        String[] parts = sortStr.split(",");
        String property = parts[0].trim();
        Sort.Direction direction = (parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc"))
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(direction, property);
    }

    private String getString(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) {
            return null;
        }
        return map.get(key).toString().trim();
    }

    private BigDecimal getBigDecimal(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) {
                if (val instanceof Number num) {
                    return BigDecimal.valueOf(num.doubleValue());
                }
                try {
                    return new BigDecimal(val.toString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private Boolean getBoolean(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) {
                if (val instanceof Boolean b) {
                    return b;
                }
                return Boolean.parseBoolean(val.toString().trim());
            }
        }
        return null;
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) {
            return null;
        }
        Object val = map.get(key);
        if (val instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
