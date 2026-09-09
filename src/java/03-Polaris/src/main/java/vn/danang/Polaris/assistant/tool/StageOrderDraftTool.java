package vn.danang.polaris.assistant.tool;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;
import vn.danang.polaris.assistant.dto.DraftItemDto;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.service.ProductService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Component
public class StageOrderDraftTool implements AssistantTool {

    private final ProductService productService;
    private final AssistantDraftService draftService;
    private final vn.danang.polaris.assistant.security.AssistantSecurityScoper securityScoper;

    @org.springframework.beans.factory.annotation.Autowired
    public StageOrderDraftTool(
            ProductService productService,
            AssistantDraftService draftService,
            vn.danang.polaris.assistant.security.AssistantSecurityScoper securityScoper) {
        this.productService = productService;
        this.draftService = draftService;
        this.securityScoper = securityScoper;
    }

    public StageOrderDraftTool(ProductService productService, AssistantDraftService draftService) {
        this(productService, draftService, new vn.danang.polaris.assistant.security.AssistantSecurityScoper(null));
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
            "stage_order_draft",
            "Stage an order draft with itemized pricing snapshots and 15-minute TTL without placing an order",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "items", Map.of(
                        "type", "array",
                        "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                "sku", Map.of("type", "string"),
                                "quantity", Map.of("type", "integer")
                            ),
                            "required", List.of("sku", "quantity")
                        )
                    ),
                    "customerId", Map.of("type", "integer", "description", "Optional customer ID override for staff")
                ),
                "required", List.of("items")
            )
        );
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> arguments, SessionContext context) {
        if (context == null || context.sessionId() == null || context.sessionId().isBlank()) {
            return ToolExecutionResult.failure("stage_order_draft", "Active session ID is required to stage an order draft.");
        }

        Long customerId = parseLong(arguments.get("customerId"));
        if (customerId != null) {
            if (securityScoper != null && !securityScoper.canActAsCustomer(customerId, context)) {
                Map<String, Object> forbiddenCard = vn.danang.polaris.assistant.widget.ProblemWidgetFactory.forForbidden(
                        "customerId",
                        customerId,
                        "Access denied: Retail shoppers cannot stage orders on behalf of other customers."
                );
                return ToolExecutionResult.failureWithWidget(
                        "stage_order_draft",
                        "Access denied: You cannot stage orders for another customer.",
                        "PROBLEM_CARD",
                        forbiddenCard
                );
            }
            if (securityScoper != null && context.isStaff() && !securityScoper.customerExists(customerId)) {
                Map<String, Object> notFoundCard = vn.danang.polaris.assistant.widget.ProblemWidgetFactory.forCustomerNotFound(customerId);
                return ToolExecutionResult.failureWithWidget(
                        "stage_order_draft",
                        "Customer not found with ID: " + customerId,
                        "PROBLEM_CARD",
                        notFoundCard
                );
            }
        } else if (context.customerId() != null) {
            customerId = context.customerId();
        } else {
            customerId = 1L; // default fallback customer
        }

        Object rawItems = arguments.get("items");
        if (!(rawItems instanceof List<?> itemsList) || itemsList.isEmpty()) {
            return ToolExecutionResult.failure("stage_order_draft", "No items provided for order draft staging.");
        }

        List<DraftItemDto> draftItems = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (Object itemObj : itemsList) {
            if (!(itemObj instanceof Map<?, ?> itemMap)) {
                continue;
            }
            String sku = itemMap.get("sku") != null ? itemMap.get("sku").toString().trim() : null;
            int quantity = parseInt(itemMap.get("quantity"), 1);

            if (sku == null || sku.isBlank()) {
                return ToolExecutionResult.failure("stage_order_draft", "Line item must specify a valid SKU.");
            }

            ProductResponse product;
            try {
                product = productService.getProductBySku(sku);
            } catch (ResourceNotFoundException e) {
                return ToolExecutionResult.failure("stage_order_draft", "Product not found with SKU: " + sku);
            }

            int availableStock = product.stockQuantity() != null ? product.stockQuantity() : 0;
            if (availableStock < quantity) {
                Map<String, Object> problemCard = vn.danang.polaris.assistant.widget.ProblemWidgetFactory.forInsufficientStock(
                        sku, product.name(), quantity, availableStock);
                return ToolExecutionResult.failureWithWidget(
                        "stage_order_draft",
                        "Insufficient stock for product " + sku,
                        "PROBLEM_CARD",
                        problemCard
                );
            }

            BigDecimal unitPrice = product.price() != null ? product.price() : BigDecimal.ZERO;
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
            grandTotal = grandTotal.add(subtotal);

            draftItems.add(new DraftItemDto(product.sku(), product.name(), quantity, unitPrice, subtotal));
        }

        if (draftItems.isEmpty()) {
            return ToolExecutionResult.failure("stage_order_draft", "No valid items could be added to the draft.");
        }

        // Stage in DB via AssistantDraftService (does NOT call OrderService.placeOrder!)
        AssistantOrderDraft savedDraft = draftService.stageDraft(
                context.sessionId(),
                customerId,
                draftItems,
                grandTotal,
                15
        );

        AssistantDraftResponse response = AssistantDraftResponse.from(savedDraft);
        return ToolExecutionResult.successWithDraft("stage_order_draft", response, response);
    }

    private Long parseLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseInt(Object val, int def) {
        if (val == null) return def;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
