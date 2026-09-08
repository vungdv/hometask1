package vn.danang.polaris.web;

import java.util.List;

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
import vn.danang.polaris.dto.CategoryResponse;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.service.CategoryService;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Product catalog categories and taxonomy exploration")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "List categories", description = "List active categories. When rootOnly=true, returns only top-level categories without parents.")
    public List<CategoryResponse> listCategories(
            @Parameter(description = "If true, returns only top-level root categories without parents")
            @RequestParam(required = false, defaultValue = "false") Boolean rootOnly) {
        return categoryService.getCategories(Boolean.TRUE.equals(rootOnly));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get category by ID", description = "Retrieve single category details by internal database ID.")
    public CategoryResponse getCategoryById(
            @Parameter(description = "Category database ID")
            @PathVariable Long id) {
        return categoryService.getCategoryById(id);
    }

    @GetMapping("/code/{code}")
    @Operation(summary = "Get category by slug/code", description = "Retrieve single category details by slug/code.")
    public CategoryResponse getCategoryByCode(
            @Parameter(description = "Category unique code/slug (e.g., audio, electronics)")
            @PathVariable String code) {
        return categoryService.getCategoryByCode(code);
    }

    @GetMapping("/{id}/products")
    @Operation(summary = "Get products under category", description = "Retrieve products under the given category (and its direct subcategories), paged.")
    public Page<ProductResponse> getCategoryProducts(
            @Parameter(description = "Category database ID")
            @PathVariable Long id,
            @PageableDefault(page = 0, size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return categoryService.getCategoryProducts(id, pageable);
    }
}
