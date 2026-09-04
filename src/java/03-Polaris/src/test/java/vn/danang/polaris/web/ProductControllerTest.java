package vn.danang.polaris.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.config.WebConfig;
import vn.danang.polaris.domain.Product;
import vn.danang.polaris.repository.ProductRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(ProductController.class)
@ImportAutoConfiguration(WebConfig.class)
@Import(SecurityConfig.class)
public class ProductControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private ProductRepository productRepository;

    @Test
    void getProduct_shouldReturnsDefaultProductInJsonFormat() throws Exception{
        when(clock.instant()).thenReturn(Instant.parse("2026-08-31T00:00:00Z"));
        
        mockMvc.perform(get("/api/v1/products/00000000-0000-0000-0000-000000000000")
                            .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.tenantId").value(""))
                .andExpect(jsonPath("$.sku").value(""))
                .andExpect(jsonPath("$.name").value(""))
                .andExpect(jsonPath("$.description").value("A new product"))
                .andExpect(jsonPath("$.price").value(0))
                .andExpect(jsonPath("$.stockQuantity").value(0))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.createdAt").value("2026-08-31T00:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-08-31T00:00:00Z"));
    }

   @Test
    void listProduct_shouldReturnDefaultProductListInJsonFormat() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-08-31T00:00:00Z"));

        Product product = new Product();
        product.setSku("SKU-001");
        product.setName("Sample Product");
        product.setPrice(BigDecimal.TEN);
        product.setStockQty(5);

        Page<Product> page = new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1);
        when(productRepository.findAll(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/products").with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-001"))
                .andExpect(jsonPath("$.content[0].name").value("Sample Product"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
