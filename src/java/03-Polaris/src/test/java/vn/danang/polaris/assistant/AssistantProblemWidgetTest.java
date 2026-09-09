package vn.danang.polaris.assistant;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;

import vn.danang.polaris.assistant.widget.ProblemWidgetFactory;
import vn.danang.polaris.web.exception.DraftExpiredException;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;
import vn.danang.polaris.web.exception.InsufficientStockException;

class AssistantProblemWidgetTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    // =========================================================================
    // 1. ProblemWidgetFactory Tests
    // =========================================================================

    @Test
    void forInsufficientStock_partialStock_includesAdjustAndSearchActions() {
        Map<String, Object> widget = ProblemWidgetFactory.forInsufficientStock("NG-CHARGER-01", "Fast Charger", 5, 2);

        assertThat(widget.get("type")).isEqualTo("https://polaris.local/errors/out-of-stock");
        assertThat(widget.get("title")).isEqualTo("Insufficient Stock");
        assertThat(widget.get("status")).isEqualTo(400);
        assertThat(widget.get("invalid_param")).isEqualTo("quantity");
        assertThat(widget.get("sku")).isEqualTo("NG-CHARGER-01");
        assertThat(widget.get("received")).isEqualTo(5);
        assertThat(widget.get("expected")).isEqualTo(2);
        assertThat(widget.get("remedy").toString()).contains("Reduce order quantity for 'NG-CHARGER-01' to 2 or fewer units");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) widget.get("actions");
        assertThat(actions).hasSize(2);

        Map<String, Object> adjust = actions.get(0);
        assertThat(adjust.get("label")).isEqualTo("Adjust Quantity to 2");
        assertThat(adjust.get("action")).isEqualTo("adjust_quantity");
        assertThat(adjust.get("sku")).isEqualTo("NG-CHARGER-01");
        assertThat(adjust.get("quantity")).isEqualTo(2);

        Map<String, Object> alt = actions.get(1);
        assertThat(alt.get("label")).isEqualTo("Search Alternatives");
        assertThat(alt.get("action")).isEqualTo("search_alternatives");
        assertThat(alt.get("query")).isEqualTo("Fast Charger");
    }

    @Test
    void forInsufficientStock_zeroStock_onlyIncludesSearchAlternativesAction() {
        Map<String, Object> widget = ProblemWidgetFactory.forInsufficientStock("NG-OUT-01", "Out of Stock Item", 3, 0);

        assertThat(widget.get("status")).isEqualTo(400);
        assertThat(widget.get("expected")).isEqualTo(0);
        assertThat(widget.get("remedy").toString()).contains("currently out of stock");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) widget.get("actions");
        assertThat(actions).hasSize(1);

        Map<String, Object> alt = actions.get(0);
        assertThat(alt.get("label")).isEqualTo("Search Alternatives");
        assertThat(alt.get("action")).isEqualTo("search_alternatives");
        assertThat(alt.get("query")).isEqualTo("Out of Stock Item");
    }

    @Test
    void forOrderStateConflict_includesReturnGuidelinesAction() {
        Map<String, Object> widget = ProblemWidgetFactory.forOrderStateConflict("ORD-1002", "DELIVERED");

        assertThat(widget.get("type")).isEqualTo("https://polaris.local/errors/conflict");
        assertThat(widget.get("title")).isEqualTo("Order State Conflict");
        assertThat(widget.get("status")).isEqualTo(409);
        assertThat(widget.get("invalid_param")).isEqualTo("status");
        assertThat(widget.get("orderNumber")).isEqualTo("ORD-1002");
        assertThat(widget.get("received")).isEqualTo("DELIVERED");
        assertThat(widget.get("allowed_values")).isEqualTo(List.of("PLACED", "CONFIRMED"));
        assertThat(widget.get("remedy").toString()).contains("return/refund workflow");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) widget.get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("label")).isEqualTo("Return Guidelines");
        assertThat(actions.get(0).get("action")).isEqualTo("view_returns");
    }

    @Test
    void forDraftExpired_includesRefreshDraftAction() {
        Map<String, Object> widget = ProblemWidgetFactory.forDraftExpired("draft-exp-123");

        assertThat(widget.get("type")).isEqualTo("https://polaris.local/errors/draft-expired");
        assertThat(widget.get("title")).isEqualTo("Draft Expired");
        assertThat(widget.get("status")).isEqualTo(409);
        assertThat(widget.get("invalid_param")).isEqualTo("draftId");
        assertThat(widget.get("received")).isEqualTo("draft-exp-123");
        assertThat(widget.get("remedy").toString()).contains("15-minute TTL elapsed");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) widget.get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("label")).isEqualTo("Refresh Draft");
        assertThat(actions.get(0).get("action")).isEqualTo("refresh_draft");
    }

    @Test
    void forForbidden_includesMyOrdersAction() {
        Map<String, Object> widget = ProblemWidgetFactory.forForbidden(
                "orderNumber", "ORD-SECRET", "Access denied to foreign order"
        );

        assertThat(widget.get("type")).isEqualTo("https://polaris.local/errors/forbidden");
        assertThat(widget.get("title")).isEqualTo("Forbidden");
        assertThat(widget.get("status")).isEqualTo(403);
        assertThat(widget.get("invalid_param")).isEqualTo("orderNumber");
        assertThat(widget.get("received")).isEqualTo("ORD-SECRET");
        assertThat(widget.get("detail")).isEqualTo("Access denied to foreign order");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) widget.get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("label")).isEqualTo("My Orders");
        assertThat(actions.get(0).get("action")).isEqualTo("view_my_orders");
    }

    @Test
    void forCustomerNotFound_returns404ProblemCard() {
        Map<String, Object> widget = ProblemWidgetFactory.forCustomerNotFound(999L);

        assertThat(widget.get("type")).isEqualTo("https://polaris.local/errors/not-found");
        assertThat(widget.get("title")).isEqualTo("Customer Not Found");
        assertThat(widget.get("status")).isEqualTo(404);
        assertThat(widget.get("invalid_param")).isEqualTo("customerId");
        assertThat(widget.get("received")).isEqualTo(999L);
    }

    // =========================================================================
    // 2. GlobalExceptionHandler RFC 7807 Problem Detail Tests
    // =========================================================================

    @Test
    void exceptionHandler_insufficientStock_enrichesWithActions() {
        InsufficientStockException ex = new InsufficientStockException("NG-CHARGER-01", 5, 2);
        ProblemDetail problem = exceptionHandler.handleInsufficientStockException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Insufficient Stock");
        assertThat(problem.getProperties()).containsKey("actions");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) problem.getProperties().get("actions");
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).get("action")).isEqualTo("adjust_quantity");
        assertThat(actions.get(0).get("quantity")).isEqualTo(2);
        assertThat(actions.get(1).get("action")).isEqualTo("search_alternatives");
    }

    @Test
    void exceptionHandler_illegalStateException_enrichesWithReturnGuidelinesAction() {
        IllegalStateException ex = new IllegalStateException("Order ORD-1002 cannot be cancelled — current status is DELIVERED");
        ProblemDetail problem = exceptionHandler.handleIllegalStateException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Order State Conflict");
        assertThat(problem.getProperties()).containsKey("actions");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) problem.getProperties().get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("action")).isEqualTo("view_returns");
    }

    @Test
    void exceptionHandler_draftExpiredException_enrichesWithRefreshDraftAction() {
        DraftExpiredException ex = new DraftExpiredException("draft-expired-456", "Draft expired");
        ProblemDetail problem = exceptionHandler.handleDraftExpiredException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Draft Expired");
        assertThat(problem.getProperties()).containsKey("actions");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) problem.getProperties().get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("action")).isEqualTo("refresh_draft");
    }

    @Test
    void exceptionHandler_accessDeniedException_enrichesWithMyOrdersAction() {
        AccessDeniedException ex = new AccessDeniedException("Access denied");
        ProblemDetail problem = exceptionHandler.handleAccessDeniedException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getTitle()).isEqualTo("Forbidden");
        assertThat(problem.getProperties()).containsKey("actions");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) problem.getProperties().get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("action")).isEqualTo("view_my_orders");
    }
}
