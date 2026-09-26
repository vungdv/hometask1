package vn.danang.polaris.assistant.tools;

/**
 * Carries a non-2xx response from Order's {@code POST /api/v1/orders} (e.g. stock exhausted
 * between the confirm endpoint's re-verification step and this call) so the caller can proxy the
 * downstream {@code ProblemDetail} as-is, per WO-021 Task 5 ("Any downstream 4xx ... proxy the
 * downstream ProblemDetail as-is; draft remains WAITING_CONFIRMATION").
 */
public class OrderPlacementRejectedException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public OrderPlacementRejectedException(int statusCode, String responseBody, Throwable cause) {
        super("Order placement rejected downstream with status " + statusCode, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
