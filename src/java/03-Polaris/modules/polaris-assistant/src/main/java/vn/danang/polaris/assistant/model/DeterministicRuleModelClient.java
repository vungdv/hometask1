package vn.danang.polaris.assistant.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;
import vn.danang.polaris.dto.ProductResponse;
import vn.danang.polaris.assistant.tool.AssistantTool;
import vn.danang.polaris.assistant.tool.ToolExecutionResult;

@Component
@ConditionalOnProperty(name = "polaris.ai.provider", havingValue = "local", matchIfMissing = true)
public class DeterministicRuleModelClient implements AssistantModelClient {

    private static final Pattern MAX_PRICE_PATTERN = Pattern.compile("under\\s*\\$?(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKU_PATTERN = Pattern.compile("(NG-[A-Z0-9-]+|OR-[A-Z0-9-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_NUMBER_PATTERN = Pattern.compile("(ORD-\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUSTOMER_ID_PATTERN = Pattern.compile("(?:for customer|customer(?: id)?)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_QTY_SKU_PATTERN = Pattern.compile("(\\d+)\\s+(?:units?\\s+of\\s+)?(NG-[A-Z0-9-]+|OR-[A-Z0-9-]+|[a-zA-Z]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public void streamChat(SessionContext context, List<AssistantTool> tools, ModelStreamListener listener) {
        String prompt = context.userPrompt() != null ? context.userPrompt().trim() : "";
        String promptLower = prompt.toLowerCase();

        // 1. Order Staging Intent: "order ...", "stage ...", "buy ..."
        if (isOrderStagingIntent(promptLower)) {
            handleOrderStagingIntent(prompt, context, tools, listener);
            listener.onDone("STOP");
            return;
        }

        // 2. Cancellation Review Intent: "cancel ORD-..."
        if (promptLower.startsWith("cancel")) {
            handleCancellationIntent(prompt, context, tools, listener);
            listener.onDone("STOP");
            return;
        }

        // 3. Stock Check Intent: "stock ...", "check stock ..."
        if (isStockCheckIntent(promptLower)) {
            handleStockCheckIntent(prompt, context, tools, listener);
            listener.onDone("STOP");
            return;
        }

        // 4. Order Tracking Intent: "status of ...", "orders", "track ..."
        if (isOrderTrackingIntent(promptLower)) {
            handleOrderTrackingIntent(prompt, context, tools, listener);
            listener.onDone("STOP");
            return;
        }

        // 5. Catalog Search Intent: "find ...", "search ...", "under $30", etc.
        if (isCatalogSearchIntent(promptLower)) {
            handleCatalogSearchIntent(prompt, context, tools, listener);
            listener.onDone("STOP");
            return;
        }

        // 6. Fallback General Assistance
        handleFallback(listener);
        listener.onDone("STOP");
    }

    private boolean isOrderStagingIntent(String lower) {
        return lower.startsWith("order ") ||
               lower.startsWith("stage ") ||
               lower.startsWith("buy ") ||
               lower.startsWith("add ") ||
               (lower.contains("order") && (lower.contains("ng-") || lower.contains("charger") || lower.contains("earbud") || lower.contains("watch")));
    }

    private boolean isStockCheckIntent(String lower) {
        return lower.startsWith("stock") ||
               lower.startsWith("check stock") ||
               lower.contains("in stock") ||
               lower.contains("available stock");
    }

    private boolean isOrderTrackingIntent(String lower) {
        return lower.contains("status") ||
               lower.equals("orders") ||
               lower.equals("my orders") ||
               lower.startsWith("track") ||
               ORDER_NUMBER_PATTERN.matcher(lower).find();
    }

    private boolean isCatalogSearchIntent(String lower) {
        return lower.startsWith("find") ||
               lower.startsWith("search") ||
               lower.startsWith("show") ||
               lower.startsWith("looking for") ||
               lower.startsWith("do we have") ||
               lower.contains("charger") ||
               lower.contains("earbud") ||
               lower.contains("speaker") ||
               lower.contains("watch") ||
               lower.contains("under ");
    }

    private void handleCatalogSearchIntent(String prompt, SessionContext context, List<AssistantTool> tools, ModelStreamListener listener) {
        BigDecimal maxPrice = null;
        Matcher priceMatcher = MAX_PRICE_PATTERN.matcher(prompt);
        if (priceMatcher.find()) {
            maxPrice = new BigDecimal(priceMatcher.group(1));
        }

        String query = extractSearchKeyword(prompt);

        listener.onThought("Searching catalog for: " + (query != null ? query : "products") + (maxPrice != null ? " (max $" + maxPrice + ")" : "") + "...");

        AssistantTool searchTool = findTool(tools, "search_products");
        if (searchTool == null) {
            listener.onToken("Catalog search tool is currently unavailable.");
            return;
        }

        Map<String, Object> args = new HashMap<>();
        if (query != null && !query.isBlank()) {
            args.put("query", query);
        }
        if (maxPrice != null) {
            args.put("maxPrice", maxPrice);
        }

        ToolExecutionResult result = searchTool.execute(args, context);
        if (!result.success() || result.output() == null) {
            listener.onToken("No products found matching your search criteria.");
            return;
        }

        if (result.output() instanceof List<?> list) {
            if (list.isEmpty()) {
                listener.onToken("No products found matching your search criteria.");
            } else if (list.size() == 1) {
                Object item = list.get(0);
                String name = (item instanceof vn.danang.polaris.dto.ProductResponse p) ? p.name() : "Product";
                String sku = (item instanceof vn.danang.polaris.dto.ProductResponse p) ? p.sku() : "";
                BigDecimal price = (item instanceof vn.danang.polaris.dto.ProductResponse p) ? p.price() : BigDecimal.ZERO;
                int stock = (item instanceof vn.danang.polaris.dto.ProductResponse p && p.stockQuantity() != null) ? p.stockQuantity() : 0;

                listener.onToken(String.format("Found %s (%s) for $%s with %d units in stock.", name, sku, price, stock));
                listener.onWidget("PRODUCT_CARD", item);
            } else {
                listener.onToken(String.format("Found %d matching products in our catalog. Which model would you prefer?", list.size()));
                listener.onWidget("PRODUCT_LIST_CARD", list);
            }
        }
    }

    private void handleStockCheckIntent(String prompt, SessionContext context, List<AssistantTool> tools, ModelStreamListener listener) {
        String sku = extractSku(prompt);
        if (sku == null) {
            listener.onToken("Please specify a valid product SKU to check stock (e.g. 'stock NG-CHARGER-01').");
            return;
        }

        listener.onThought("Checking live inventory for SKU: " + sku + "...");

        AssistantTool stockTool = findTool(tools, "get_product_stock");
        if (stockTool == null) {
            listener.onToken("Stock inquiry tool is currently unavailable.");
            return;
        }

        ToolExecutionResult result = stockTool.execute(Map.of("sku", sku), context);
        if (!result.success()) {
            listener.onToken(result.error() != null ? result.error() : "Unable to find stock details for " + sku + ".");
            return;
        }

        if (result.output() instanceof vn.danang.polaris.dto.ProductResponse p) {
            int stock = p.stockQuantity() != null ? p.stockQuantity() : 0;
            listener.onToken(String.format("Product %s (%s) currently has %d units in stock at $%s.",
                    p.name(), p.sku(), stock, p.price()));
            listener.onWidget("PRODUCT_CARD", p);
        }
    }

    private void handleOrderStagingIntent(String prompt, SessionContext context, List<AssistantTool> tools, ModelStreamListener listener) {
        listener.onThought("Verifying catalog inventory and staging order draft...");

        AssistantTool stageTool = findTool(tools, "stage_order_draft");
        if (stageTool == null) {
            listener.onToken("Order staging tool is currently unavailable.");
            return;
        }

        List<Map<String, Object>> items = parseItemsFromPrompt(prompt);
        if (items.isEmpty()) {
            listener.onToken("Please specify product SKU and quantity to stage an order (e.g. 'order 2 NG-CHARGER-01').");
            return;
        }

        Long customerId = extractCustomerId(prompt);
        if (customerId == null && context.customerId() != null) {
            customerId = context.customerId();
        }

        Map<String, Object> args = new HashMap<>();
        args.put("items", items);
        if (customerId != null) {
            args.put("customerId", customerId);
        }

        ToolExecutionResult result = stageTool.execute(args, context);
        if (!result.success()) {
            if (result.widgetPayload() != null && "PROBLEM_CARD".equals(result.widgetType())) {
                listener.onWidget("PROBLEM_CARD", result.widgetPayload());
                listener.onToken(result.error() != null ? result.error() : "Insufficient stock to fulfill order draft.");
            } else {
                listener.onToken("Failed to stage order draft: " + (result.error() != null ? result.error() : "unknown error."));
            }
            return;
        }

        if (result.draftPayload() != null) {
            AssistantDraftResponse draft = result.draftPayload();
            listener.onDraft(draft);
            listener.onWidget("DRAFT_CARD", draft);
            listener.onToken(String.format("Order draft staged totaling $%s (%d item(s)). This draft is held for 15 minutes. Please review and confirm to submit your order.",
                    draft.totalAmount(), draft.items().size()));
        }
    }

    private void handleCancellationIntent(String prompt, SessionContext context, List<AssistantTool> tools, ModelStreamListener listener) {
        String orderNumber = extractOrderNumber(prompt);
        if (orderNumber == null) {
            listener.onToken("Please specify an order number to cancel (e.g. 'cancel ORD-1001').");
            return;
        }

        listener.onThought("Evaluating cancellation eligibility for order: " + orderNumber + "...");

        AssistantTool cancelReviewTool = findTool(tools, "cancel_order_review");
        if (cancelReviewTool == null) {
            listener.onToken("Cancellation review tool is currently unavailable.");
            return;
        }

        ToolExecutionResult result = cancelReviewTool.execute(Map.of("orderNumber", orderNumber), context);
        if (!result.success()) {
            if (result.widgetPayload() != null) {
                listener.onWidget("PROBLEM_CARD", result.widgetPayload());
            }
            String msg = (result.error() != null) ? result.error() : ("Order " + orderNumber + " cannot be cancelled.");
            listener.onToken(msg);
            return;
        }

        if (result.widgetPayload() instanceof Map<?, ?> map) {
            Object notice = map.get("restockNotice");
            listener.onWidget("CANCELLATION_REVIEW_CARD", map);
            listener.onToken("Order " + orderNumber + " is eligible for cancellation. " + (notice != null ? notice.toString() : "") + " Please confirm cancellation.");
        }
    }

    private void handleOrderTrackingIntent(String prompt, SessionContext context, List<AssistantTool> tools, ModelStreamListener listener) {
        String orderNumber = extractOrderNumber(prompt);
        if (orderNumber == null) {
            listener.onToken("Here are your recent orders or please specify an order number to track (e.g. 'status of ORD-1001').");
            return;
        }

        listener.onThought("Looking up tracking details for order: " + orderNumber + "...");

        AssistantTool statusTool = findTool(tools, "get_order_status");
        if (statusTool == null) {
            listener.onToken("Order status tool is currently unavailable.");
            return;
        }

        ToolExecutionResult result = statusTool.execute(Map.of("orderNumber", orderNumber), context);
        if (!result.success()) {
            if (result.widgetPayload() != null && "PROBLEM_CARD".equals(result.widgetType())) {
                listener.onWidget("PROBLEM_CARD", result.widgetPayload());
            }
            String msg = (result.error() != null) ? result.error() : ("Order " + orderNumber + " was not found.");
            listener.onToken(msg);
            return;
        }

        if (result.output() instanceof vn.danang.polaris.dto.OrderResponse o) {
            listener.onToken(String.format("Order %s is currently %s with grand total $%s.",
                    o.orderNumber(), o.status(), o.totalAmount()));
            listener.onWidget("ORDER_STATUS_CARD", o);
        }
    }

    private void handleFallback(ModelStreamListener listener) {
        listener.onToken("I can help you search our catalog (e.g. 'find chargers under $30'), check live stock (e.g. 'stock NG-CHARGER-01'), stage order drafts (e.g. 'order 2 NG-CHARGER-01'), track orders (e.g. 'status of ORD-1001'), or review cancellations.");
    }

    private AssistantTool findTool(List<AssistantTool> tools, String name) {
        if (tools == null) return null;
        return tools.stream().filter(t -> {
            if (t.getDefinition() != null && t.getDefinition().name() != null && t.getDefinition().name().equalsIgnoreCase(name)) {
                return true;
            }
            return t.getName() != null && t.getName().equalsIgnoreCase(name);
        }).findFirst().orElse(null);
    }

    private String extractSearchKeyword(String prompt) {
        String clean = prompt.replaceAll("(?i)(find|search|show|do we have|looking for|under\\s*\\$?(\\d+(?:\\.\\d+)?)|in stock)", "").trim();
        if (clean.isBlank()) {
            if (prompt.toLowerCase().contains("charger")) return "charger";
            if (prompt.toLowerCase().contains("earbud")) return "earbud";
            if (prompt.toLowerCase().contains("watch")) return "watch";
            if (prompt.toLowerCase().contains("speaker")) return "speaker";
        }
        return clean.isBlank() ? null : clean;
    }

    private String extractSku(String prompt) {
        Matcher matcher = SKU_PATTERN.matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        return null;
    }

    private String extractOrderNumber(String prompt) {
        Matcher matcher = ORDER_NUMBER_PATTERN.matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        return null;
    }

    private Long extractCustomerId(String prompt) {
        Matcher matcher = CUSTOMER_ID_PATTERN.matcher(prompt);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private List<Map<String, Object>> parseItemsFromPrompt(String prompt) {
        List<Map<String, Object>> items = new ArrayList<>();
        Matcher skuMatcher = SKU_PATTERN.matcher(prompt);
        List<String> skus = new ArrayList<>();
        while (skuMatcher.find()) {
            skus.add(skuMatcher.group(1).toUpperCase());
        }

        if (!skus.isEmpty()) {
            for (String sku : skus) {
                // Find quantity preceding or following sku
                int qty = 1;
                Pattern qtyBefore = Pattern.compile("(\\d+)\\s+(?:units?\\s+of\\s+)?" + Pattern.quote(sku), Pattern.CASE_INSENSITIVE);
                Matcher qb = qtyBefore.matcher(prompt);
                if (qb.find()) {
                    qty = Integer.parseInt(qb.group(1));
                }
                items.add(Map.of("sku", sku, "quantity", qty));
            }
            return items;
        }

        // Generic fallback item parsing: e.g. "order 2 chargers" -> NG-CHARGER-01
        if (prompt.toLowerCase().contains("charger")) {
            int qty = 1;
            Matcher qm = Pattern.compile("(\\d+)\\s+chargers?", Pattern.CASE_INSENSITIVE).matcher(prompt);
            if (qm.find()) qty = Integer.parseInt(qm.group(1));
            items.add(Map.of("sku", "NG-CHARGER-01", "quantity", qty));
        } else if (prompt.toLowerCase().contains("earbud")) {
            int qty = 1;
            Matcher qm = Pattern.compile("(\\d+)\\s+earbuds?", Pattern.CASE_INSENSITIVE).matcher(prompt);
            if (qm.find()) qty = Integer.parseInt(qm.group(1));
            items.add(Map.of("sku", "NG-EARBUD-01", "quantity", qty));
        } else if (prompt.toLowerCase().contains("watch")) {
            int qty = 1;
            Matcher qm = Pattern.compile("(\\d+)\\s+watch(?:es)?", Pattern.CASE_INSENSITIVE).matcher(prompt);
            if (qm.find()) qty = Integer.parseInt(qm.group(1));
            items.add(Map.of("sku", "NG-WATCH-01", "quantity", qty));
        }

        return items;
    }
}
