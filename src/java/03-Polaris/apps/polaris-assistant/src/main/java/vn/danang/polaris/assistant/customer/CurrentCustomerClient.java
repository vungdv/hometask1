package vn.danang.polaris.assistant.customer;

import java.util.Optional;

/**
 * Resolves the customer account linked to a signed-in caller through Order Management's published
 * contract {@code GET /api/v1/customers/me}. Order Management decides the link from the verified token
 * (JWT {@code sub}, one-time fallback on the verified {@code email}); the assistant never trusts a
 * customer id supplied by the model for a shopper.
 */
public interface CurrentCustomerClient {

    /**
     * @param callerBearerToken the caller's own access token (never a service token)
     * @return the linked customer id, or empty if Order Management has no customer linked to the caller
     * @throws CustomerLookupException if the lookup itself failed (unreachable, rejected token, bad payload)
     */
    Optional<Long> findCurrentCustomerId(String callerBearerToken);
}
