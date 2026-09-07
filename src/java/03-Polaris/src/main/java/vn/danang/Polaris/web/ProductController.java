package vn.danang.polaris.web;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.service.ProductService;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Product catalog exploration and availability search")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "Search products", description = "Search and filter products by query keyword, category, price range, and stock availability.")
    public Page<ProductResponse> search(
            @Parameter(description = "Keyword to match against product name, SKU, or description")
            @RequestParam(required = false) String query,
            @Parameter(description = "Category filter (e.g., Audio, Wearables, Accessories)")
            @RequestParam(required = false) String category,
            @Parameter(description = "Minimum unit price")
            @RequestParam(required = false) BigDecimal minPrice,
            @Parameter(description = "Maximum unit price")
            @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "Filter by availability in stock (true: stock_qty > 0 and active, false: out of stock)")
            @RequestParam(required = false) Boolean available,
            @PageableDefault(page = 0, size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {

        return productService.searchProducts(query, category, minPrice, maxPrice, available, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID", description = "Retrieve single product details by internal numeric ID.")
    public ProductResponse getProductById(
            @Parameter(description = "Product database ID")
            @PathVariable Long id) {
        return productService.getProductById(id);
    }

    @GetMapping("/sku/{sku}")
    @Operation(summary = "Get product by SKU", description = "Retrieve single product details by business SKU code.")
    public ProductResponse getProductBySku(
            @Parameter(description = "Product SKU (e.g., NG-EARBUD-01)")
            @PathVariable String sku) {
        return productService.getProductBySku(sku);
    }
}
