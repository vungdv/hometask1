package vn.danang.polaris.repository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import vn.danang.polaris.entity.Product;

@SpringBootTest
public class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Test
    void searchAvailableProducts_shouldFilterOutOfStockItems() {
        Specification<Product> spec = Specification
                .where(ProductSpecifications.isAvailable(true));

        Page<Product> availableProducts = productRepository.findAll(spec, PageRequest.of(0, 20));

        // The seed data has 5 in-stock and 1 out-of-stock ('NG-STAND-01')
        assertThat(availableProducts.getContent()).isNotEmpty();
        assertThat(availableProducts.getContent())
                .allMatch(p -> p.getStockQty() > 0 && Boolean.TRUE.equals(p.getIsActive()));
        assertThat(availableProducts.getContent())
                .noneMatch(p -> "NG-STAND-01".equals(p.getSku()));
    }

    @Test
    void searchByKeyword_shouldMatchAcrossNameSkuAndDescription() {
        Specification<Product> spec = Specification
                .where(ProductSpecifications.hasKeyword("noise cancellation"));

        List<Product> results = productRepository.findAll(spec);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSku()).isEqualTo("NG-EARBUD-01");
    }

    @Test
    void searchByPriceRangeAndCategory() {
        Specification<Product> spec = Specification
                .where(ProductSpecifications.hasCategory("Accessories"))
                .and(ProductSpecifications.minPrice(new BigDecimal("20.00")))
                .and(ProductSpecifications.maxPrice(new BigDecimal("30.00")));

        List<Product> results = productRepository.findAll(spec);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSku()).isEqualTo("NG-CHARGER-01");
    }

    @Test
    void findBySkuIgnoreCase_shouldReturnProduct() {
        var productOpt = productRepository.findBySkuIgnoreCase("ng-watch-01");
        assertThat(productOpt).isPresent();
        assertThat(productOpt.get().getName()).isEqualTo("Nova Smart Watch");
    }
}
