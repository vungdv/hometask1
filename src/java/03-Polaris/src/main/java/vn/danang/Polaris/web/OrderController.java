package vn.danang.polaris.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.danang.polaris.entity.Order;
import vn.danang.polaris.service.OrderService;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{orderNumber}/status")
    public ResponseEntity<?> getStatus(@PathVariable String orderNumber) {
        Order order = orderService.getOrderStatus(orderNumber);
        return ResponseEntity.ok(order);
    }

    @PostMapping("/{orderNumber}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String orderNumber) {
        Order order = orderService.cancelOrder(orderNumber);
        return ResponseEntity.ok(order);
    }
}