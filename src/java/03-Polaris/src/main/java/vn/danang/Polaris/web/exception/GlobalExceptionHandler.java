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
        problem.setProperty("min", ex.getMin());
        problem.setProperty("max", ex.getMax());
        problem.setProperty("received", ex.getReceived());
        return problem;
    }

    @ExceptionHandler(InvalidSortPropertyException.class)
    public ProblemDetail handleInvalidSortPropertyException(InvalidSortPropertyException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Sort Property");
        problem.setType(URI.create("https://polaris.local/errors/invalid-sort"));
        problem.setProperty("invalid_property", ex.getInvalidProperty());
        problem.setProperty("allowed_properties", ex.getAllowedProperties());
        return problem;
    }

    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ProblemDetail handleInvalidDataAccessApiUsageException(InvalidDataAccessApiUsageException ex) {

        String msg = ex.getMessage();
        if (msg != null && (msg.contains("Sort expression") || msg.contains("property references or aliases"))) {
            Matcher matcher = SORT_PROPERTY_PATTERN.matcher(msg);
            if (matcher.find()) {
                String prop = matcher.group(1);
                return handleInvalidSortPropertyException(new InvalidSortPropertyException(prop));
            }
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

