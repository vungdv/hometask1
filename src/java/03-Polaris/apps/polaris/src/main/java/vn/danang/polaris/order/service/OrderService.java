package vn.danang.polaris.order.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.dto.OrderResponse;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.entity.OrderItem;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.order.repository.CustomerRepository;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.order.repository.OrderSpecifications;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.config.CacheConfig;
import vn.danang.polaris.web.exception.IdempotencyKeyReusedException;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.PriceChangedException;
import vn.danang.polaris.web.exception.ProductInactiveException;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Length of {@code orders.idempotency_key} (V5). */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    /**
     * Result of {@link #place}: the order, and whether it was an existing order returned for a repeated
     * {@code Idempotency-Key} rather than a newly created one.
     */
    public record Placement(Order order, boolean replayed) {}

    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;
    private final CacheManager cacheManager;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate replayTx;

    public OrderService(OrderRepository orderRepo, ProductRepository productRepo, CustomerRepository customerRepo,
                        CacheManager cacheManager, PlatformTransactionManager transactionManager) {
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
        this.cacheManager = cacheManager;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.replayTx = new TransactionTemplate(transactionManager);
        this.replayTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.replayTx.setReadOnly(true);
    }

    private String generateOrderNumber() {
        return String.format("ORD-%06d", orderRepo.nextOrderNumberValue());
    }

    public Order placeOrder(Long customerId, List<OrderItemRequest> requestedItems, String idempotencyKey) {
        return place(customerId, requestedItems, idempotencyKey).order();
    }

    /**
     * Places an order, or returns the existing one when {@code idempotencyKey} was already used by the same customer.
     * <p>
     * Lines repeating a SKU (case-insensitive) are merged and checked against stock as one line, like the quote.
     * Under row lock, every product must still be sold ({@link ProductInactiveException}), every line with an
     * {@code expectedUnitPrice} must still match the live price ({@link PriceChangedException}, FR-14), and every
     * line must have enough stock ({@link InsufficientStockException}); any failure rolls back with no order and
     * no stock change.
     * <p>
     * Deliberately not {@code @Transactional}: the write runs in its own transaction so that a request losing an
     * idempotency-key race (unique index {@code idx_orders_idempotency_key}) can discard the aborted Postgres
     * transaction and re-read the winner's order in a fresh one, so both callers get the same order.
     *
     * @throws IdempotencyKeyReusedException when the key belongs to another customer's order
     */
    public Placement place(Long customerId, List<OrderItemRequest> requestedItems, String idempotencyKey) {
        if (customerId == null) {
            throw new IllegalArgumentException("Customer ID is required");
        }
        List<MergedLine> lines = mergeLines(requestedItems);
        String key = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null;
        if (key != null && key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }

        try {
            return writeTx.execute(status -> placeInTransaction(customerId, lines, key));
        } catch (DataIntegrityViolationException ex) {
            if (key == null) {
                throw ex;
            }
            // A concurrent request with the same key committed its order between our look-ups and our insert
            Optional<Placement> winner = replayTx.execute(status ->
                    orderRepo.findByIdempotencyKey(key).map(existing -> replay(existing, customerId)));
            if (winner == null || winner.isEmpty()) {
                throw ex;
            }
            log.info("Order placement lost idempotency race, returning existing order: orderNumber={}, customerId={}",
                    winner.get().order().getOrderNumber(), customerId);
            return winner.get();
        }
    }

    private record MergedLine(String sku, int quantity, List<BigDecimal> expectedUnitPrices) {}

    private Placement placeInTransaction(Long customerId, List<MergedLine> lines, String key) {
        if (key != null) {
            Optional<Order> existing = orderRepo.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), customerId);
            }
        }

        Customer customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with ID: " + customerId));

        // Phase 1: lock every product row, in SKU order so concurrent multi-item orders cannot deadlock
        record LockedLine(Product product, MergedLine line) {}
        List<LockedLine> locked = new ArrayList<>();
        for (MergedLine line : lines) {
            Product product = productRepo.findBySkuIgnoreCaseForUpdate(line.sku())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + line.sku()));
            locked.add(new LockedLine(product, line));
        }

        if (key != null) {
            // A same-key request holding these locks may have committed while we waited for them
            Optional<Order> existing = orderRepo.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), customerId);
            }
        }

        // Phase 2: verify active status, price and stock for all lines before modifying any state
        List<String> inactive = locked.stream()
                .filter(l -> Boolean.FALSE.equals(l.product().getIsActive()))
                .map(l -> l.product().getSku())
                .toList();
        if (!inactive.isEmpty()) {
            log.info("Order rejected, inactive products: customerId={}, inactiveSkus={}", customerId, inactive.size());
            throw new ProductInactiveException(inactive);
        }
        List<PriceChangedException.ChangedLine> changed = new ArrayList<>();
        for (LockedLine l : locked) {
            BigDecimal livePrice = l.product().getPrice();
            l.line().expectedUnitPrices().stream()
                    .filter(expected -> livePrice == null || expected.compareTo(livePrice) != 0)
                    .findFirst()
                    .ifPresent(expected -> changed.add(
                            new PriceChangedException.ChangedLine(l.product().getSku(), expected, livePrice)));
        }
        if (!changed.isEmpty()) {
            log.info("Order rejected, price changed since confirmation: customerId={}, changedLines={}",
                    customerId, changed.size());
            throw new PriceChangedException(changed);
        }
        for (LockedLine l : locked) {
            int currentStock = l.product().getStockQty() != null ? l.product().getStockQty() : 0;
            if (currentStock < l.line().quantity()) {
                throw new InsufficientStockException(l.product().getSku(), l.line().quantity(), currentStock);
            }
        }

        // Phase 3: stock deduction and order construction
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PLACED);
        order.setOrderNumber(generateOrderNumber());
        order.setPlacedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order.setIdempotencyKey(key);

        BigDecimal total = BigDecimal.ZERO;
        List<Product> touched = new ArrayList<>();
        for (LockedLine l : locked) {
            Product product = l.product();
            int quantity = l.line().quantity();

            product.setStockQty(product.getStockQty() - quantity);
            productRepo.save(product);
            touched.add(product);

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(product);
            orderItem.setQuantity(quantity);
            orderItem.setUnitPrice(product.getPrice());
            order.getItems().add(orderItem);

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }
        order.setTotalAmount(total);

        Order saved = orderRepo.save(order);
        evictProductsAfterCommit(touched);
        log.info("Order placed: orderNumber={}, customerId={}, lines={}, total={}",
                saved.getOrderNumber(), customerId, locked.size(), total);
        return new Placement(saved, false);
    }

    private Placement replay(Order existing, Long customerId) {
        if (existing.getCustomer() == null || !customerId.equals(existing.getCustomer().getId())) {
            log.warn("Idempotency key reused by another customer: existingOrderNumber={}, customerId={}",
                    existing.getOrderNumber(), customerId);
            throw new IdempotencyKeyReusedException();
        }
        initialize(existing);
        return new Placement(existing, true);
    }

    /**
     * Validates the lines and merges those repeating a SKU (case-insensitive) into one with the summed quantity,
     * sorted by SKU so row locks are always taken in the same order.
     */
    private static List<MergedLine> mergeLines(List<OrderItemRequest> requestedItems) {
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }
        Map<String, MergedLine> merged = new LinkedHashMap<>();
        for (OrderItemRequest item : requestedItems) {
            if (item == null || item.sku() == null || item.sku().isBlank()) {
                throw new IllegalArgumentException("Item 'sku' is required");
            }
            if (item.quantity() == null || item.quantity() < 1) {
                throw new IllegalArgumentException("Item 'quantity' must be at least 1 for SKU '" + item.sku() + "'");
            }
            String sku = item.sku().trim();
            List<BigDecimal> expected = item.expectedUnitPrice() != null ? List.of(item.expectedUnitPrice()) : List.of();
            merged.merge(sku.toLowerCase(Locale.ROOT), new MergedLine(sku, item.quantity(), expected), (a, b) -> {
                long sum = (long) a.quantity() + b.quantity();
                if (sum > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Total quantity for SKU '" + a.sku() + "' is too large");
                }
                List<BigDecimal> prices = new ArrayList<>(a.expectedUnitPrices());
                prices.addAll(b.expectedUnitPrices());
                return new MergedLine(a.sku(), (int) sum, prices);
            });
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(line -> line.sku().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * Evicts the cached {@code products} entries (id and {@link CacheConfig#productSkuKey} keys) of the given
     * products once the current transaction commits, so {@code get_product_by_sku} never serves pre-order stock.
     * A rollback leaves the cache untouched, since nothing changed.
     */
    private void evictProductsAfterCommit(List<Product> products) {
        List<Object> keys = new ArrayList<>();
        for (Product product : products) {
            keys.add(product.getId());
            keys.add(CacheConfig.productSkuKey(product.getSku()));
        }
        Runnable evict = () -> {
            Cache cache = cacheManager.getCache(CacheConfig.PRODUCTS_CACHE);
            if (cache != null) {
                keys.forEach(cache::evict);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }

    /** Loads the lazy associations the order DTOs and MCP formatters read, while the session is still open. */
    private static void initialize(Order order) {
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
        List<Product> touched = new ArrayList<>();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                if (product != null && item.getQuantity() != null) {
                    Product lockedProduct = productRepo.findBySkuIgnoreCaseForUpdate(product.getSku())
                            .orElse(product);
                    int currentStock = lockedProduct.getStockQty() != null ? lockedProduct.getStockQty() : 0;
                    lockedProduct.setStockQty(currentStock + item.getQuantity());
                    productRepo.save(lockedProduct);
                    touched.add(lockedProduct);
                }
            }
        }
        evictProductsAfterCommit(touched);

        return orderRepo.save(order);
    }

    @Transactional(readOnly = true)
    public Order getOrderStatus(String orderNumber) {
        Order order = orderRepo.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with order number: " + orderNumber));
        initialize(order);
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