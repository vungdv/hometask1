package vn.danang.polaris.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.service.ProductService;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(ProductController.class)
@ImportAutoConfiguration(WebConfig.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void searchProducts_shouldReturnPagedResults() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L,
                "NG-EARBUD-01",
                "Nova Wireless Earbuds",
                "High-fidelity wireless earbuds",
                "Audio",
                new BigDecimal("49.90"),
                120,
                true,
                true,
                Instant.now()
        );

        Page<ProductResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 20), 1);
        when(productService.searchProducts(eq("wireless"), eq("Audio"), any(), any(), eq(true), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .param("query", "wireless")
                        .param("category", "Audio")
                        .param("available", "true")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.content[0].name").value("Nova Wireless Earbuds"))
                .andExpect(jsonPath("$.content[0].category").value("Audio"))
                .andExpect(jsonPath("$.content[0].price").value(49.90))
                .andExpect(jsonPath("$.content[0].stockQuantity").value(120))
                .andExpect(jsonPath("$.content[0].isAvailable").value(true))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getProductById_whenFound_shouldReturnProduct() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L,
                "NG-EARBUD-01",
                "Nova Wireless Earbuds",
                "High-fidelity wireless earbuds",
                "Audio",
                new BigDecimal("49.90"),
                120,
                true,
                true,
                Instant.now()
        );
        when(productService.getProductById(1L)).thenReturn(sample);

        mockMvc.perform(get("/api/v1/products/1").with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.name").value("Nova Wireless Earbuds"));
    }

    @Test
    void getProductById_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(productService.getProductById(999L))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 999"));

        mockMvc.perform(get("/api/v1/products/999").with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product not found with id: 999"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void getProductBySku_whenFound_shouldReturnProduct() throws Exception {
        ProductResponse sample = new ProductResponse(
                2L,
                "NG-WATCH-01",
                "Nova Smart Watch",
                "Advanced smartwatch",
                "Wearables",
                new BigDecimal("89.90"),
                60,
                true,
                true,
                Instant.now()
        );
        when(productService.getProductBySku("NG-WATCH-01")).thenReturn(sample);

        mockMvc.perform(get("/api/v1/products/sku/NG-WATCH-01").with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.sku").value("NG-WATCH-01"))
                .andExpect(jsonPath("$.category").value("Wearables"));
    }

    @Test
    void getProductBySku_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(productService.getProductBySku("NON-EXISTENT"))
                .thenThrow(new ResourceNotFoundException("Product not found with SKU: NON-EXISTENT"));

        mockMvc.perform(get("/api/v1/products/sku/NON-EXISTENT").with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product not found with SKU: NON-EXISTENT"));
    }
}
