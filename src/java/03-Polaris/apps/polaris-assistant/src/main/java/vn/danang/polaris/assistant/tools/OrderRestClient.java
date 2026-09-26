package vn.danang.polaris.assistant.tools;

import java.math.BigDecimal;
import java.util.List;

import vn.danang.polaris.assistant.dto.OrderItemRequest;

/**
 * Typed client for Order's published, stable {@code POST /api/v1/orders} contract. Confirming a
 * draft (WO-021) calls this — never {@code OrderService} directly, never an Order-context JPA
 * repository.
 */
public interface OrderRestClient {

    /**
     * One placed order's essentials, as reported by Order. {@code rawResponseBody} is the exact
     * JSON body Order returned — the confirm endpoint proxies it byte-for-byte as its own 201
     * response rather than re-shaping it, per this WO's Task 5.
     */
    record OrderPlacedView(String orderNumber, String status, BigDecimal totalAmount, String rawResponseBody) {
    }

    /**
     * @throws OrderPlacementRejectedException on any non-2xx response, carrying the downstream
     *         status code and raw {@code ProblemDetail} body so the caller can proxy it as-is
     */
    OrderPlacedView placeOrder(Long customerId, List<OrderItemRequest> items, String idempotencyKey);
}
