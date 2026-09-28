package vn.danang.polaris.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.dto.QuoteRequest;
import vn.danang.polaris.order.dto.QuoteResponse;

/**
 * Read-only stock and price verification for a prospective order (FR-14, BPMN {@code O_Verify}).
 * <p>
 * Reads products straight from the database (never the {@code products} cache, FR-1), takes no row
 * locks and writes nothing. The result is advisory: {@link OrderService#placeOrder} re-checks under
 * row lock at commit time.
 */
@Service
@Transactional(readOnly = true)
public class OrderQuoteService {

    private static final Logger log = LoggerFactory.getLogger(OrderQuoteService.class);

    private final ProductRepository productRepo;

    public OrderQuoteService(ProductRepository productRepo) {
        this.productRepo = productRepo;
    }

    public QuoteResponse quote(List<QuoteRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Quote must contain at least one item");
        }

        Map<String, Optional<Product>> products = new HashMap<>();
        // Repeated SKUs draw on the same stock, so shortage is judged on the cumulative quantity.
        Map<String, Integer> requestedSoFar = new HashMap<>();

        List<QuoteResponse.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean orderable = true;

        for (QuoteRequest.Item item : items) {
            if (item == null || item.sku() == null || item.sku().isBlank()) {
                throw new IllegalArgumentException("Item 'sku' is required");
            }
            if (item.quantity() == null || item.quantity() < 1) {
                throw new IllegalArgumentException("Item 'quantity' must be at least 1 for SKU '" + item.sku() + "'");
            }
            String sku = item.sku().trim();
            String key = sku.toLowerCase(Locale.ROOT);
            int quantity = item.quantity();

            Optional<Product> found = products.computeIfAbsent(key, k -> productRepo.findBySkuIgnoreCase(sku));
            if (found.isEmpty()) {
                lines.add(new QuoteResponse.Line(sku, null, quantity, null, null, null, QuoteResponse.Problem.NOT_FOUND));
                orderable = false;
                continue;
            }

            Product product = found.get();
            BigDecimal unitPrice = product.getPrice();
            int available = product.getStockQty() != null ? product.getStockQty() : 0;
            int cumulative = requestedSoFar.merge(key, quantity, Integer::sum);

            QuoteResponse.Problem problem = null;
            if (Boolean.FALSE.equals(product.getIsActive())) {
                problem = QuoteResponse.Problem.INACTIVE;
            } else if (cumulative > available) {
                problem = QuoteResponse.Problem.INSUFFICIENT_STOCK;
            }

            BigDecimal lineTotal = null;
            if (problem == null) {
                lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
                total = total.add(lineTotal);
            } else {
                orderable = false;
            }
            lines.add(new QuoteResponse.Line(
                    product.getSku(), product.getName(), quantity, unitPrice, available, lineTotal, problem));
        }

        log.debug("Order quote computed: lines={}, orderable={}, total={}", lines.size(), orderable, total);
        return new QuoteResponse(orderable, lines, total);
    }
}
