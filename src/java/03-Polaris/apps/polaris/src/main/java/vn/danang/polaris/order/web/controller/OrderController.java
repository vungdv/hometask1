package vn.danang.polaris.order.web.controller;

import java.net.URI;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import vn.danang.polaris.order.dto.CreateOrderRequest;
import vn.danang.polaris.order.dto.OrderResponse;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.web.validator.PageableValidator;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order lifecycle management, placement, history search, and status tracking")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(
        summary = "Place a new order",
        description = "Create and submit a new multi-item purchase order with live stock deduction and idempotency support."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Order successfully created or existing order returned for idempotent retry",
            headers = @Header(name = HttpHeaders.LOCATION, description = "URI of the created order resource", schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Validation failure, out-of-stock inventory, or invalid input parameters",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(
            responseCode = "404",
            description = "Customer ID or product SKU does not exist",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        )
    })
    public ResponseEntity<OrderResponse> placeOrder(
            @Parameter(description = "Optional idempotency key header to prevent duplicate orders during retries")
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        String idempotencyKey = (headerIdempotencyKey != null && !headerIdempotencyKey.isBlank())
                ? headerIdempotencyKey.trim()
                : (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                        ? request.idempotencyKey().trim()
                        : null);

        Order order = orderService.placeOrder(request.customerId(), request.items(), idempotencyKey);
        URI location = URI.create("/api/v1/orders/" + order.getOrderNumber());
        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderNumber}")
    @Operation(summary = "Get order details", description = "Retrieve full order details, line items, and fulfillment status by business order number.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order details successfully retrieved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "404", description = "Order not found with the specified order number",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> getOrder(
            @Parameter(description = "Unique business order number (e.g. 'ORD-1001')", required = true)
            @PathVariable String orderNumber) {
        Order order = orderService.getOrderStatus(orderNumber);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping("/{orderNumber}/status")
    @Operation(summary = "Get order status", description = "Retrieve order details and status by business order number (backwards compatible endpoint).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order status successfully retrieved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "404", description = "Order not found with the specified order number",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> getStatus(
            @Parameter(description = "Unique business order number (e.g. 'ORD-1001')", required = true)
            @PathVariable String orderNumber) {
        Order order = orderService.getOrderStatus(orderNumber);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping
    @Operation(
        summary = "Search customer orders",
        description = "Query customer order history with optional filtering by customer ID and order status, supporting pagination and sorting."
    )
    @Parameters({
        @Parameter(name = "customerId", description = "Filter by customer ID (must be > 0)", schema = @Schema(type = "integer", minimum = "1")),
        @Parameter(name = "status", description = "Filter by order lifecycle status", schema = @Schema(implementation = OrderStatus.class)),
        @Parameter(name = "page", description = "Zero-based page index (0..10000)", schema = @Schema(type = "integer", defaultValue = "0", minimum = "0", maximum = "10000")),
        @Parameter(name = "size", description = "The size of the page to be returned (1..100)", schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
        @Parameter(name = "sort", description = "Sorting criteria in the format: property(,asc|desc). Allowed properties: [id, orderNumber, status, totalAmount, placedAt, updatedAt]", example = "placedAt,desc", schema = @Schema(type = "string", defaultValue = "placedAt,desc"))
    })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of orders matching search criteria successfully retrieved"),
        @ApiResponse(responseCode = "400", description = "Invalid pagination, sorting, or customer ID parameter",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token")
    })
    public ResponseEntity<Page<OrderResponse>> searchOrders(
            @Parameter(description = "Customer ID filter")
            @RequestParam(required = false) Long customerId,
            @Parameter(description = "Order status filter")
            @RequestParam(required = false) OrderStatus status,
            @Parameter(hidden = true)
            @PageableDefault(page = 0, size = 20, sort = "placedAt", direction = Sort.Direction.DESC) Pageable pageable) {

        if (customerId != null && customerId <= 0) {
            throw new IllegalArgumentException("Customer ID must be greater than 0. Received: " + customerId);
        }

        Pageable sanitized = PageableValidator.validateAndSanitizeOrder(pageable);
        Page<OrderResponse> result = orderService.searchOrders(customerId, status, sanitized);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{orderNumber}/cancel")
    @Operation(summary = "Cancel order", description = "Cancel an order in PLACED or CONFIRMED status, restoring inventory stock.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order successfully cancelled and stock restored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "404", description = "Order not found with the specified order number",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Order is in a non-cancellable state (e.g. PARCELED, DELIVERING, DELIVERED, CANCELLED)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> cancel(
            @Parameter(description = "Unique business order number to cancel (e.g. 'ORD-1001')", required = true)
            @PathVariable String orderNumber) {
        Order order = orderService.cancelOrder(orderNumber);
        return ResponseEntity.ok(OrderResponse.from(order));
    }
}