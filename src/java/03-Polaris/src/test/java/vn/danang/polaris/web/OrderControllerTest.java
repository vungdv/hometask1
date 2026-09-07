package vn.danang.polaris.web;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.config.WebConfig;
import vn.danang.polaris.entity.Customer;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.entity.OrderItem;
import vn.danang.polaris.entity.OrderStatus;
import vn.danang.polaris.entity.Product;
import vn.danang.polaris.service.OrderService;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(OrderController.class)
@ImportAutoConfiguration(WebConfig.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    private Order createSampleOrder(String orderNumber, OrderStatus status) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber(orderNumber);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("99.90"));
        order.setPlacedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        Customer customer = new Customer();
        customer.setId(1L);
        customer.setFullName("Alice Tran");
        order.setCustomer(customer);

        Product product = new Product();
        product.setId(10L);
        product.setSku("NG-EARBUD-01");
        product.setName("Nova Wireless Earbuds");
        product.setPrice(new BigDecimal("49.95"));

        OrderItem item = new OrderItem();
        item.setId(100L);
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.95"));

        order.getItems().add(item);
        return order;
    }

    @Test
    void getStatus_whenFound_shouldReturnOrderResponse() throws Exception {
        Order sample = createSampleOrder("ORD-1002", OrderStatus.CONFIRMED);
        when(orderService.getOrderStatus("ORD-1002")).thenReturn(sample);

        mockMvc.perform(get("/api/v1/orders/ORD-1002/status").with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1002"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(99.90))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.items[0].productName").value("Nova Wireless Earbuds"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(49.95))
                .andExpect(jsonPath("$.items[0].subtotal").value(99.90));
    }

    @Test
    void getStatus_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(orderService.getOrderStatus("ORD-9999"))
                .thenThrow(new ResourceNotFoundException("Order not found with order number: ORD-9999"));

        mockMvc.perform(get("/api/v1/orders/ORD-9999/status").with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order not found with order number: ORD-9999"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void getStatus_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancel_whenCancellable_shouldReturnCancelledOrder() throws Exception {
        Order sample = createSampleOrder("ORD-1001", OrderStatus.CANCELLED);
        when(orderService.cancelOrder("ORD-1001")).thenReturn(sample);

        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel").with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_whenNotCancellable_shouldReturn409Conflict() throws Exception {
        when(orderService.cancelOrder("ORD-1005"))
                .thenThrow(new IllegalStateException("Order ORD-1005 cannot be cancelled — current status is DELIVERED"));

        mockMvc.perform(post("/api/v1/orders/ORD-1005/cancel").with(JwtMockFactory.user()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order State Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Order ORD-1005 cannot be cancelled — current status is DELIVERED"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/conflict"));
    }

    @Test
    void cancel_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(orderService.cancelOrder("ORD-9999"))
                .thenThrow(new ResourceNotFoundException("Order not found with order number: ORD-9999"));

        mockMvc.perform(post("/api/v1/orders/ORD-9999/cancel").with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order not found with order number: ORD-9999"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void cancel_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel"))
                .andExpect(status().isUnauthorized());
    }
}
