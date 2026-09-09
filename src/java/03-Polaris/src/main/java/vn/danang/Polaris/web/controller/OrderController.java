package vn.danang.polaris.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.danang.polaris.dto.OrderResponse;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.service.OrderService;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order lifecycle management and status tracking")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{orderNumber}/status")
    @Operation(summary = "Get order status", description = "Retrieve order details and status by business order number.")
    public ResponseEntity<OrderResponse> getStatus(@PathVariable String orderNumber) {
        Order order = orderService.getOrderStatus(orderNumber);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @PostMapping("/{orderNumber}/cancel")
    @Operation(summary = "Cancel order", description = "Cancel an order if it is in PLACED or CONFIRMED status.")
    public ResponseEntity<OrderResponse> cancel(@PathVariable String orderNumber) {
        Order order = orderService.cancelOrder(orderNumber);
        return ResponseEntity.ok(OrderResponse.from(order));
    }
}