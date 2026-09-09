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
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.service.ProductService;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(ProductController.class)
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
        when(productService.searchProducts(eq("wireless"), eq("Audio"), eq(null), any(), any(), eq(true), any(Pageable.class)))
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
    void searchProducts_withCategoryId_shouldFilterByCategoryId() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L,
                "NG-EARBUD-01",
                "Nova Wireless Earbuds",
                "High-fidelity wireless earbuds",
                "Audio & Sound",
                new BigDecimal("49.90"),
                120,
                true,
                true,
                Instant.now(),
                2L,
                "audio"
        );

        Page<ProductResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 20), 1);
        when(productService.searchProducts(any(), any(), eq(2L), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", "2")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].categoryId").value(2))
                .andExpect(jsonPath("$.content[0].categoryCode").value("audio"));
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

    @Test
    void searchProducts_invalidSortProperty_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("sort", "string")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Sort Property"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-sort"))
                .andExpect(jsonPath("$.detail").value("Invalid sort property 'string'. Allowed sort properties are: [id, sku, name, category, price, stockQuantity, stockQty, active, createdAt]. Format: property(,asc|desc)."))
                .andExpect(jsonPath("$.invalid_property").value("string"))
                .andExpect(jsonPath("$.allowed_properties", hasSize(9)));
    }

    @Test
    void searchProducts_pageSizeTooLarge_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("size", "1073741824")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Pagination Parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-pagination"))
                .andExpect(jsonPath("$.detail").value("Page size must be between 1 and 100. Received: 1073741824."))
                .andExpect(jsonPath("$.invalid_param").value("size"))
                .andExpect(jsonPath("$.min").value(1))
                .andExpect(jsonPath("$.max").value(100))
                .andExpect(jsonPath("$.received").value(1073741824));
    }

    @Test
    void searchProducts_pageSizeBelowOne_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("size", "0")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Pagination Parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-pagination"))
                .andExpect(jsonPath("$.detail").value("Page size must be between 1 and 100. Received: 0."))
                .andExpect(jsonPath("$.invalid_param").value("size"))
                .andExpect(jsonPath("$.min").value(1))
                .andExpect(jsonPath("$.max").value(100))
                .andExpect(jsonPath("$.received").value(0));
    }

    @Test
    void searchProducts_pageIndexTooLarge_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("page", "1073741824")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Pagination Parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-pagination"))
                .andExpect(jsonPath("$.detail").value("Page index must be between 0 and 10000. Received: 1073741824."))
                .andExpect(jsonPath("$.invalid_param").value("page"))
                .andExpect(jsonPath("$.min").value(0))
                .andExpect(jsonPath("$.max").value(10000))
                .andExpect(jsonPath("$.received").value(1073741824));
    }

    @Test
    void searchProducts_pageIndexNegative_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("page", "-1")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Pagination Parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-pagination"))
                .andExpect(jsonPath("$.detail").value("Page index must be between 0 and 10000. Received: -1."))
                .andExpect(jsonPath("$.invalid_param").value("page"))
                .andExpect(jsonPath("$.min").value(0))
                .andExpect(jsonPath("$.max").value(10000))
                .andExpect(jsonPath("$.received").value(-1));
    }

    @Test
    void searchProducts_sortByAliasStockQuantity_shouldReturn200() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L, "NG-EARBUD-01", "Nova Wireless Earbuds", "Description",
                "Audio", new BigDecimal("49.90"), 120, true, true, Instant.now()
        );
        Page<ProductResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 20), 1);
        when(productService.searchProducts(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .param("sort", "stockQuantity,desc")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void searchProducts_validSortAndPagination_shouldReturn200() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L, "NG-EARBUD-01", "Nova Wireless Earbuds", "Description",
                "Audio", new BigDecimal("49.90"), 120, true, true, Instant.now()
        );
        Page<ProductResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 10), 1);
        when(productService.searchProducts(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .param("page", "0")
                        .param("size", "10")
                        .param("sort", "price,asc")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }
}
