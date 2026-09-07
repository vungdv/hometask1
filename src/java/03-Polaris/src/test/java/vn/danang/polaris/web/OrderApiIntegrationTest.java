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

    @Test
    void cancel_placedOrder_shouldTransitionToCancelled() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_deliveredOrder_shouldReturn409ConflictProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-1005/cancel")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order State Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/conflict"));
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
        mockMvc.perform(get("/api/v1/orders/ORD-1002/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void distributedTracingPropagation_shouldReturnTraceIdInHeader() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002/status")
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"));
    }
}
