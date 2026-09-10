package vn.danang.polaris.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.entity.OrderStatus;
import vn.danang.polaris.repository.OrderRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class OrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.findByOrderNumber("ORD-1001").ifPresent(order -> {
            order.setStatus(OrderStatus.PLACED);
            orderRepository.save(order);
        });
    }

    @Test
    void getStatus_seededOrder_shouldReturnConfirmedOrder() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002/status")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1002"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Autowired
    private vn.danang.polaris.repository.ProductRepository productRepository;

    @Test
    void placeOrder_multiItem_shouldDeductStockAndReturn201() throws Exception {
        int initialEarbudStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        int initialChargerStock = productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty();

        String payload = """
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-EARBUD-01", "quantity": 1 },
                { "sku": "NG-CHARGER-01", "quantity": 2 }
              ]
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.orderNumber").exists())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(99.70))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.items").isArray());

        int finalEarbudStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        int finalChargerStock = productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty();
        org.assertj.core.api.Assertions.assertThat(finalEarbudStock).isEqualTo(initialEarbudStock - 1);
        org.assertj.core.api.Assertions.assertThat(finalChargerStock).isEqualTo(initialChargerStock - 2);
    }

    @Test
    void placeOrder_insufficientStock_shouldReturn400OutOfStockAndNotDeductStock() throws Exception {
        int initialWatchStock = productRepository.findBySku("NG-WATCH-01").orElseThrow().getStockQty();
        long initialOrderCount = orderRepository.count();

        String payload = String.format("""
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-WATCH-01", "quantity": %d }
              ]
            }
            """, initialWatchStock + 10);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Insufficient Stock"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/out-of-stock"))
                .andExpect(jsonPath("$.sku").value("NG-WATCH-01"))
                .andExpect(jsonPath("$.available_quantity").value(initialWatchStock))
                .andExpect(jsonPath("$.remedy").exists());

        int finalWatchStock = productRepository.findBySku("NG-WATCH-01").orElseThrow().getStockQty();
        long finalOrderCount = orderRepository.count();
        org.assertj.core.api.Assertions.assertThat(finalWatchStock).isEqualTo(initialWatchStock);
        org.assertj.core.api.Assertions.assertThat(finalOrderCount).isEqualTo(initialOrderCount);
    }

    @Test
    void placeOrder_idempotentRetry_shouldReturnSameOrderWithoutDuplicateDeduction() throws Exception {
        int initialEarbudStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();

        String payload = """
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-EARBUD-01", "quantity": 1 }
              ],
              "idempotencyKey": "idem-key-scenario-3"
            }
            """;

        // First attempt
        String response1 = mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        int afterFirstStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        org.assertj.core.api.Assertions.assertThat(afterFirstStock).isEqualTo(initialEarbudStock - 1);

        com.fasterxml.jackson.databind.JsonNode rootNode1 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response1);
        String orderNumber1 = rootNode1.get("orderNumber").asText();

        // Second attempt with same idempotency key
        String response2 = mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode rootNode2 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response2);
        String orderNumber2 = rootNode2.get("orderNumber").asText();

        org.assertj.core.api.Assertions.assertThat(orderNumber2).isEqualTo(orderNumber1);

        int afterSecondStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        org.assertj.core.api.Assertions.assertThat(afterSecondStock).isEqualTo(afterFirstStock);
    }

    @Test
    void getOrder_byOrderNumber_shouldReturnFullDetails() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1002"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.totalAmount").value(89.90))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void searchOrders_byCustomerId_shouldReturnPaginatedOrders() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", "1")
                        .param("page", "0")
                        .param("size", "10")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void cancel_placedOrder_shouldTransitionToCancelledAndRestoreStock() throws Exception {
        int earbudStockBefore = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();

        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        int earbudStockAfter = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        org.assertj.core.api.Assertions.assertThat(earbudStockAfter).isEqualTo(earbudStockBefore + 1);
    }

    @Test
    void cancel_deliveredOrder_shouldReturn409ConflictProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-1005/cancel")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order State Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/conflict"))
                .andExpect(jsonPath("$.allowed_states_for_action").isArray())
                .andExpect(jsonPath("$.remedy").exists());
    }

    @Test
    void getStatus_missingOrder_shouldReturn404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-9999/status")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void unauthenticatedRequest_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void distributedTracingPropagation_shouldReturnTraceIdInHeader() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002")
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"));
    }
}
