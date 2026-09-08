package vn.danang.polaris.dto;

import java.util.Collections;
import java.util.List;

import vn.danang.polaris.entity.Category;

public record CategoryResponse(
    Long id,
    String code,
    String name,
    String description,
    Long parentId,
    String parentName,
    List<CategoryResponse> subcategories,
    Long productCount
) {
    public static CategoryResponse from(Category category, Long productCount, List<CategoryResponse> subcategories) {
        Long parentId = category.getParent() != null ? category.getParent().getId() : null;
        String parentName = category.getParent() != null ? category.getParent().getName() : null;

        return new CategoryResponse(
            category.getId(),
            category.getCode(),
            category.getName(),
            category.getDescription(),
            parentId,
            parentName,
            subcategories != null ? subcategories : Collections.emptyList(),
            productCount != null ? productCount : 0L
        );
    }

    public static CategoryResponse from(Category category, Long productCount) {
        return from(category, productCount, Collections.emptyList());
    }

    public static CategoryResponse from(Category category) {
        return from(category, 0L, Collections.emptyList());
    }
}
