package vn.danang.polaris.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.domain.Customer;
import vn.danang.polaris.domain.Order;
import vn.danang.polaris.domain.OrderItem;
import vn.danang.polaris.domain.OrderStatus;
import vn.danang.polaris.domain.Product;
import vn.danang.polaris.repository.CustomerRepository;
import vn.danang.polaris.repository.OrderRepository;
import vn.danang.polaris.repository.ProductRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;

    public OrderService(OrderRepository orderRepo, ProductRepository productRepo, CustomerRepository customerRepo) {
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
    }

    @Transactional
    public Order placeOrder(Long customerId, List<OrderItem> requestedItems) {
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PLACED);
        order.setOrderNumber("ORD-" + System.currentTimeMillis() % 100000);
        order.setPlacedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : requestedItems) {
            Product product = productRepo.findById(item.getProduct().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found"));
            item.setUnitPrice(product.getPrice());
            item.setOrder(order);
            order.getItems().add(item);
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        order.setTotalAmount(total);
        return orderRepo.save(order);
    }

    @Transactional
    public Order cancelOrder(String orderNumber) {
        Order order = orderRepo.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderNumber));

        if (!order.getStatus().isCancellable()) {
            throw new IllegalStateException(
                "Order " + orderNumber + " cannot be cancelled — current status is " + order.getStatus());
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(Instant.now());
        return orderRepo.save(order);
    }

    public Order getOrderStatus(String orderNumber) {
        return orderRepo.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderNumber));
    }
}