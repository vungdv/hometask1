package vn.danang.polaris.controller;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.config.WebConfig;

@WebMvcTest(ProductController.class)
@ImportAutoConfiguration(WebConfig.class)
public class ProductControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Clock clock;

    @Test
    void getProduct_shouldReturnsDefaultProductInJsonFormat() throws Exception{
        when(clock.instant()).thenReturn(Instant.parse("2026-08-31T00:00:00Z"));

        mockMvc.perform(get("/api/v1/products/00000000-0000-0000-0000-000000000000"))
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

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());
                // .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // .andExpect(jsonPath("$").isArray())
                // // .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                // .andExpect(jsonPath("$[0].id").exists())
                // .andExpect(jsonPath("$[0].tenantId").value(""))
                // .andExpect(jsonPath("$[0].sku").value(""))
                // .andExpect(jsonPath("$[0].name").value(""))
                // .andExpect(jsonPath("$[0].description").value("A new product"))
                // .andExpect(jsonPath("$[0].price").value(0))
                // .andExpect(jsonPath("$[0].stockQuantity").value(0))
                // .andExpect(jsonPath("$[0].active").value(false))
                // .andExpect(jsonPath("$[0].createdAt").value("2026-08-31T00:00:00Z"))
                // .andExpect(jsonPath("$[0].updatedAt").value("2026-08-31T00:00:00Z"));
    }
}
