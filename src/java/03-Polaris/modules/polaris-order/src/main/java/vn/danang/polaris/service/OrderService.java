package vn.danang.polaris.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.dto.OrderItemRequest;
import vn.danang.polaris.dto.OrderResponse;
import vn.danang.polaris.entity.Customer;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.entity.OrderItem;
import vn.danang.polaris.entity.OrderStatus;
import vn.danang.polaris.entity.Product;
import vn.danang.polaris.repository.CustomerRepository;
import vn.danang.polaris.repository.OrderRepository;
import vn.danang.polaris.repository.OrderSpecifications;
import vn.danang.polaris.repository.ProductRepository;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Service
public class OrderService {

    private static final AtomicLong ORDER_COUNTER = new AtomicLong(System.currentTimeMillis() % 1000000);

    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;

    public OrderService(OrderRepository orderRepo, ProductRepository productRepo, CustomerRepository customerRepo) {
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
    }

    private String generateOrderNumber() {
        long count = Math.abs(ORDER_COUNTER.incrementAndGet());
        return String.format("ORD-%05d", count);
    }

    @Transactional
    public Order placeOrder(Long customerId, List<OrderItemRequest> requestedItems, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Order> existingOrder = orderRepo.findByIdempotencyKey(idempotencyKey.trim());
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
        }

        if (customerId == null) {
            throw new IllegalArgumentException("Customer ID is required");
        }
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with ID: " + customerId));

        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }

        record ResolvedItem(Product product, int quantity) {}
        List<ResolvedItem> resolvedItems = new ArrayList<>();

        // Phase 1: Atomicity verification - check stock for all items before modifying any state
        for (OrderItemRequest itemReq : requestedItems) {
            Product product = productRepo.findBySkuIgnoreCase(itemReq.sku())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + itemReq.sku()));

            int currentStock = product.getStockQty() != null ? product.getStockQty() : 0;
            if (currentStock < itemReq.quantity()) {
                throw new InsufficientStockException(product.getSku(), itemReq.quantity(), currentStock);
            }
            resolvedItems.add(new ResolvedItem(product, itemReq.quantity()));
        }

        // Phase 2: Stock deduction and order construction
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PLACED);
        order.setOrderNumber(generateOrderNumber());
        order.setPlacedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            order.setIdempotencyKey(idempotencyKey.trim());
        }

        BigDecimal total = BigDecimal.ZERO;
        for (ResolvedItem resolved : resolvedItems) {
            Product product = resolved.product();
            int quantity = resolved.quantity();

            product.setStockQty(product.getStockQty() - quantity);
            productRepo.save(product);

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(product);
            orderItem.setQuantity(quantity);
            orderItem.setUnitPrice(product.getPrice());
            order.getItems().add(orderItem);

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }
        order.setTotalAmount(total);

        return orderRepo.save(order);
    }

    @Transactional
    public Order placeOrder(Long customerId, List<OrderItem> requestedItems) {
        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with ID: " + customerId));

        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PLACED);
        order.setOrderNumber(generateOrderNumber());
        order.setPlacedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : requestedItems) {
            Product product = productRepo.findById(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + item.getProduct().getId()));
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
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with order number: " + orderNumber));

        if (!order.getStatus().isCancellable()) {
            throw new IllegalStateException(
                "Order " + orderNumber + " cannot be cancelled — current status is " + order.getStatus());
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(Instant.now());

        // Restore inventory stock for all line items
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                if (product != null && item.getQuantity() != null) {
                    int currentStock = product.getStockQty() != null ? product.getStockQty() : 0;
                    product.setStockQty(currentStock + item.getQuantity());
                    productRepo.save(product);
                }
            }
        }

        return orderRepo.save(order);
    }

    @Transactional(readOnly = true)
    public Order getOrderStatus(String orderNumber) {
        Order order = orderRepo.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with order number: " + orderNumber));
        if (order.getCustomer() != null) {
            order.getCustomer().getFullName();
        }
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() != null) {
                    item.getProduct().getName();
                    item.getProduct().getSku();
                }
            }
        }
        return order;
    }


    @Transactional(readOnly = true)
    public Page<OrderResponse> searchOrders(Long customerId, OrderStatus status, Pageable pageable) {
        Specification<Order> spec = Specification
                .where(OrderSpecifications.hasCustomerId(customerId))
                .and(OrderSpecifications.hasStatus(status));

        return orderRepo.findAll(spec, pageable).map(OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return orderRepo.findByIdempotencyKey(idempotencyKey);
    }
}