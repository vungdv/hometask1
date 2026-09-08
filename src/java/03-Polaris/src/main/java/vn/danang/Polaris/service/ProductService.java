package vn.danang.polaris.service;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.entity.Product;
import vn.danang.polaris.repository.ProductRepository;
import vn.danang.polaris.repository.ProductSpecifications;
import vn.danang.polaris.web.ResourceNotFoundException;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Page<ProductResponse> searchProducts(
            String query,
            String category,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Boolean available,
            Pageable pageable) {
        return searchProducts(query, category, null, minPrice, maxPrice, available, pageable);
    }

    public Page<ProductResponse> searchProducts(
            String query,
            String category,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Boolean available,
            Pageable pageable) {

        Specification<Product> spec = Specification
                .where(ProductSpecifications.hasKeyword(query))
                .and(ProductSpecifications.hasCategory(category))
                .and(ProductSpecifications.hasCategoryId(categoryId))
                .and(ProductSpecifications.minPrice(minPrice))
                .and(ProductSpecifications.maxPrice(maxPrice))
                .and(ProductSpecifications.isAvailable(available));

        return productRepository.findAll(spec, pageable)
                .map(ProductResponse::from);
    }

    public ProductResponse getProductById(Long id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
    }

    public ProductResponse getProductBySku(String sku) {
        return productRepository.findBySkuIgnoreCase(sku)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
    }
}
