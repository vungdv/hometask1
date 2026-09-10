package vn.danang.polaris.web.controller;

import java.math.BigDecimal;
import java.net.URI;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import vn.danang.polaris.dto.CreateProductRequest;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.dto.UpdateInventoryRequest;
import vn.danang.polaris.entity.Product;
import vn.danang.polaris.service.ProductService;
import vn.danang.polaris.web.validator.PageableValidator;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Product catalog exploration and availability search")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "Search products", description = "Search and filter products by query keyword, category, category ID, price range, and stock availability.")
    @Parameters({
        @Parameter(name = "page", description = "Zero-based page index (0..10000)", schema = @Schema(type = "integer", defaultValue = "0", minimum = "0", maximum = "10000")),
        @Parameter(name = "size", description = "The size of the page to be returned (1..100)", schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
        @Parameter(name = "sort", description = "Sorting criteria in the format: property(,asc|desc). Allowed properties: [id, sku, name, category, price, stockQuantity, stockQty, active, createdAt]", example = "id,asc", schema = @Schema(type = "string", defaultValue = "id,asc"))
    })
    public Page<ProductResponse> search(
            @Parameter(description = "Keyword to match against product name, SKU, or description")
            @RequestParam(required = false) String query,
            @Parameter(description = "Category filter by code or name (e.g., Audio, Wearables, Accessories)")
            @RequestParam(required = false) String category,
            @Parameter(description = "Category ID filter (matches products in category or its subcategories)")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "Minimum unit price")
            @RequestParam(required = false) BigDecimal minPrice,
            @Parameter(description = "Maximum unit price")
            @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "Filter by availability in stock (true: stock_qty > 0 and active, false: out of stock)")
            @RequestParam(required = false) Boolean available,
            @Parameter(hidden = true)
            @PageableDefault(page = 0, size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        // See ~/docs/adr/0002-pagination-sort-validation-architecture.md 
        // for details on the decision of this validation and sanitization approach.
        Pageable sanitized = PageableValidator.validateAndSanitize(pageable);
        return productService.searchProducts(query, category, categoryId, minPrice, maxPrice, available, sanitized);
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

    @PostMapping
    @Operation(summary = "Create product", description = "Onboard a new product into the catalog with case-insensitive unique SKU, price, and optional category/inventory.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Product successfully created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload or validation constraint violation",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Associated category not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Duplicate SKU collision",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = productService.createProduct(request);
        URI location = URI.create("/api/v1/products/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}/inventory")
    @Operation(summary = "Update product inventory by ID", description = "Update or adjust the available in-stock inventory count for a product by internal numeric ID with pessimistic write locking.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inventory successfully updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid quantity, missing parameter, or negative stock adjustment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Product not found with ID",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ProductResponse> updateInventoryById(
            @Parameter(description = "Product database ID")
            @PathVariable Long id,
            @Valid @RequestBody UpdateInventoryRequest request) {
        Product updated;
        if (request.delta() != null) {
            updated = productService.adjustInventoryById(id, request.delta());
        } else if (request.quantity() != null) {
            if (request.quantity() < 0) {
                throw new IllegalArgumentException("Stock quantity cannot be negative: " + request.quantity());
            }
            updated = productService.updateInventoryById(id, request.quantity());
        } else {
            throw new IllegalArgumentException("Either quantity or delta must be provided");
        }
        return ResponseEntity.ok(ProductResponse.from(updated));
    }

    @PutMapping("/sku/{sku}/inventory")
    @Operation(summary = "Update product inventory", description = "Update or adjust the available in-stock inventory count for a product with pessimistic write locking.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inventory successfully updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProductResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid quantity or negative stock adjustment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Product not found with SKU",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ProductResponse> updateInventory(
            @Parameter(description = "Product SKU (e.g., NG-EARBUD-01)")
            @PathVariable String sku,
            @Valid @RequestBody UpdateInventoryRequest request) {
        Product updated;
        if (request.delta() != null) {
            updated = productService.adjustInventory(sku, request.delta());
        } else if (request.quantity() != null) {
            if (request.quantity() < 0) {
                throw new IllegalArgumentException("Stock quantity cannot be negative: " + request.quantity());
            }
            updated = productService.updateInventory(sku, request.quantity());
        } else {
            throw new IllegalArgumentException("Either quantity or delta must be provided");
        }
        return ResponseEntity.ok(ProductResponse.from(updated));
    }
}
