package vn.danang.polaris.web.exception;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;
import vn.danang.polaris.web.validator.PageableValidator;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern SORT_PROPERTY_PATTERN =
            Pattern.compile("Sort expression '(?:\\[\\\\\"|\\[\"|\"|')?([^'\"\\]:, ]+)");

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFoundException(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setType(URI.create("https://polaris.local/errors/not-found"));
        return problem;
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ProblemDetail handleInvalidPaginationException(InvalidPaginationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Pagination Parameter");
        problem.setType(URI.create("https://polaris.local/errors/invalid-pagination"));
        problem.setProperty("invalid_param", ex.getInvalidParam());
        problem.setProperty("location", "query");
        problem.setProperty("min", ex.getMin());
        problem.setProperty("max", ex.getMax());
        problem.setProperty("received", ex.getReceived());
        problem.setProperty("remedy", String.format("Set parameter '%s' to an integer between %d and %d. Example: ?%s=%d",
                ex.getInvalidParam(), ex.getMin(), ex.getMax(), ex.getInvalidParam(), ex.getMin()));
        return problem;
    }

    @ExceptionHandler(InvalidSortPropertyException.class)
    public ProblemDetail handleInvalidSortPropertyException(InvalidSortPropertyException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Sort Property");
        problem.setType(URI.create("https://polaris.local/errors/invalid-sort"));
        problem.setProperty("invalid_property", ex.getInvalidProperty());
        problem.setProperty("allowed_properties", ex.getAllowedProperties());
        problem.setProperty("invalid_param", "sort");
        problem.setProperty("location", "query");
        problem.setProperty("received", ex.getInvalidProperty());
        problem.setProperty("allowed_values", ex.getAllowedProperties());
        String sampleProp = ex.getAllowedProperties().isEmpty() ? "id" : ex.getAllowedProperties().get(0);
        problem.setProperty("remedy", "Sort by one of the allowed properties in 'allowed_properties' with optional direction. Example: ?sort=" + sampleProp + ",asc");
        return problem;
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStockException(InsufficientStockException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Insufficient Stock");
        problem.setType(URI.create("https://polaris.local/errors/out-of-stock"));
        problem.setProperty("sku", ex.getSku());
        problem.setProperty("invalid_param", "quantity");
        problem.setProperty("requested_quantity", ex.getRequestedQuantity());
        problem.setProperty("available_quantity", ex.getAvailableQuantity());
        problem.setProperty("expected", Math.max(0, ex.getAvailableQuantity()));
        problem.setProperty("received", ex.getRequestedQuantity());
        problem.setProperty("remedy", String.format("Reduce order quantity for '%s' to %d or fewer units.",
                ex.getSku(), ex.getAvailableQuantity()));

        java.util.List<java.util.Map<String, Object>> actions = new java.util.ArrayList<>();
        if (ex.getAvailableQuantity() > 0) {
            java.util.Map<String, Object> adjust = new java.util.LinkedHashMap<>();
            adjust.put("label", "Adjust Quantity to " + ex.getAvailableQuantity());
            adjust.put("action", "adjust_quantity");
            adjust.put("sku", ex.getSku());
            adjust.put("quantity", ex.getAvailableQuantity());
            actions.add(adjust);
        }
        java.util.Map<String, Object> alt = new java.util.LinkedHashMap<>();
        alt.put("label", "Search Alternatives");
        alt.put("action", "search_alternatives");
        alt.put("query", ex.getSku());
        actions.add(alt);

        problem.setProperty("actions", actions);
        return problem;
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValidException(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for request body.");
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://polaris.local/errors/validation-error"));

        java.util.List<java.util.Map<String, Object>> errors = new java.util.ArrayList<>();
        for (org.springframework.validation.FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            java.util.Map<String, Object> err = new java.util.LinkedHashMap<>();
            err.put("field", fieldError.getField());
            err.put("rejected", fieldError.getRejectedValue());
            err.put("message", fieldError.getDefaultMessage());
            err.put("remedy", "Provide a valid value for " + fieldError.getField() + ".");
            errors.add(err);
        }
        problem.setProperty("errors", errors);
        if (!errors.isEmpty()) {
            problem.setProperty("invalid_param", errors.get(0).get("field"));
            problem.setProperty("received", errors.get(0).get("rejected"));
            problem.setProperty("remedy", errors.get(0).get("remedy"));
        }
        return problem;
    }

    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ProblemDetail handleInvalidDataAccessApiUsageException(InvalidDataAccessApiUsageException ex) {
        // TODO:
        // Are there any alternatives to parsing the exception message? 
        // This is a bit fragile, but Spring Data JPA doesn't provide a better way to handle this.
        String msg = ex.getMessage();
        if (msg != null && (msg.contains("Sort expression") || msg.contains("property references or aliases"))) {
            Matcher matcher = SORT_PROPERTY_PATTERN.matcher(msg);
            if (matcher.find()) {
                String prop = matcher.group(1);
                return handleInvalidSortPropertyException(new InvalidSortPropertyException(prop));
            }

            // TODO:
            // Different entity may have differient allowed sort properties, 
            // so this one will soon become outdated. Consider using a more dynamic approach if possible.
            String detail = "Invalid sort property. Allowed sort properties are: "
                    + PageableValidator.ALLOWED_SORT_PROPERTIES + ". Format: property(,asc|desc).";
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
            problem.setTitle("Invalid Sort Property");
            problem.setType(URI.create("https://polaris.local/errors/invalid-sort"));
            problem.setProperty("allowed_properties", PageableValidator.ALLOWED_SORT_PROPERTIES);
            return problem;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://polaris.local/errors/bad-request"));
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        if ("page".equals(paramName)) {
            long received = parseLongOrDefault(ex.getValue(), -1L);
            return handleInvalidPaginationException(new InvalidPaginationException("page", 0, 10000, received));
        }
        if ("size".equals(paramName)) {
            long received = parseLongOrDefault(ex.getValue(), 0L);
            return handleInvalidPaginationException(new InvalidPaginationException("size", 1, 100, received));
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + paramName + "': " + ex.getValue()
        );
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://polaris.local/errors/bad-request"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        String msg = ex.getMessage();
        if (msg != null) {
            // Handle specific pagination errors based on the exception message
            // It's a bit fragile, but Spring Data JPA doesn't provide a better way to handle this.
            if (msg.contains("Page index must not be less than zero")) {
                long page = -1;
                if (request != null && request.getParameter("page") != null) {
                    page = parseLongOrDefault(request.getParameter("page"), -1L);
                }
                return handleInvalidPaginationException(new InvalidPaginationException("page", 0, 10000, page));
            }
            if (msg.contains("Page size must not be less than one")) {
                long size = 0;
                if (request != null && request.getParameter("size") != null) {
                    size = parseLongOrDefault(request.getParameter("size"), 0L);
                }
                return handleInvalidPaginationException(new InvalidPaginationException("size", 1, 100, size));
            }
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://polaris.local/errors/bad-request"));
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalStateException(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Order State Conflict");
        problem.setType(URI.create("https://polaris.local/errors/conflict"));
        problem.setProperty("allowed_states_for_action", java.util.List.of("PLACED", "CONFIRMED"));
        problem.setProperty("remedy", "Only orders in PLACED or CONFIRMED state can be cancelled. Shipped orders must go through the return/refund workflow.");
        problem.setProperty("actions", java.util.List.of(java.util.Map.of("label", "Return Guidelines", "action", "view_returns")));
        return problem;
    }

    @ExceptionHandler(DraftExpiredException.class)
    public ProblemDetail handleDraftExpiredException(DraftExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Draft Expired");
        problem.setType(URI.create("https://polaris.local/errors/draft-expired"));
        if (ex.getDraftId() != null) {
            problem.setProperty("draftId", ex.getDraftId());
        }
        problem.setProperty("remedy", "The order draft has expired (15-minute TTL elapsed). Please stage a new order draft.");
        problem.setProperty("actions", java.util.List.of(java.util.Map.of("label", "Refresh Draft", "action", "refresh_draft")));
        return problem;
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ProblemDetail handleDuplicateSkuException(DuplicateSkuException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Duplicate SKU Conflict");
        problem.setType(URI.create("https://polaris.local/errors/duplicate-sku"));
        problem.setProperty("sku", ex.getSku());
        problem.setProperty("remedy", "Choose a unique SKU code or update the existing product.");
        return problem;
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicateResourceException(DuplicateResourceException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Duplicate Resource Conflict");
        problem.setType(URI.create("https://polaris.local/errors/conflict"));
        if (ex.getResourceType() != null) {
            problem.setProperty("resource_type", ex.getResourceType());
        }
        if (ex.getIdentifier() != null) {
            problem.setProperty("identifier", ex.getIdentifier());
        }
        problem.setProperty("remedy", "Verify resource uniqueness or update the existing record.");
        return problem;
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDeniedException(org.springframework.security.access.AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://polaris.local/errors/forbidden"));
        problem.setProperty("remedy", "You do not have permission to access this resource.");
        problem.setProperty("actions", java.util.List.of(java.util.Map.of("label", "My Orders", "action", "view_my_orders")));
        return problem;
    }

    private static long parseLongOrDefault(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

