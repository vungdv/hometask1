package vn.danang.polaris.order.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.dto.CustomerSummaryResponse;
import vn.danang.polaris.order.dto.UpdateCustomerRequest;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.mapper.CustomerMapper;
import vn.danang.polaris.order.repository.CustomerRepository;
import vn.danang.polaris.order.security.CallerIdentity;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

/**
 * Domain service for customer lookup operations within the order bounded context.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private static final int MAX_SEARCH_LIMIT = 20;

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    @Autowired
    public CustomerService(CustomerRepository customerRepository, CustomerMapper customerMapper) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
    }

    public CustomerService(CustomerRepository customerRepository) {
        this(customerRepository, Mappers.getMapper(CustomerMapper.class));
    }

    /**
     * Retrieve a customer profile by its primary ID.
     *
     * @param id numeric customer ID
     * @return populated CustomerResponse DTO mapped via MapStruct
     * @throws ResourceNotFoundException if no customer exists with given ID
     */
    public CustomerResponse getCustomerById(Long id) {
        return customerRepository.findById(id)
                .map(customerMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + id));
    }

    /**
     * Resolve the customer linked to the authenticated caller (decision D1).
     * Looks up by JWT {@code sub} first. If nothing is linked yet and the token carries a verified
     * {@code email}, the customer with that email is linked once by writing its {@code auth_subject};
     * a customer already linked to a different subject is never re-linked.
     *
     * @param caller authenticated caller identity
     * @return the caller's own customer entity
     * @throws ResourceNotFoundException if no customer is linked to the caller
     */
    @Transactional
    public Customer resolveCurrentCustomer(CallerIdentity caller) {
        if (caller == null || caller.subject() == null || caller.subject().isBlank()) {
            throw new ResourceNotFoundException("No customer is linked to the authenticated user.");
        }
        return customerRepository.findByAuthSubject(caller.subject())
                .or(() -> linkByVerifiedEmail(caller))
                .orElseThrow(() -> new ResourceNotFoundException("No customer is linked to the authenticated user."));
    }

    /**
     * Profile of the customer linked to the authenticated caller ({@code GET /api/v1/customers/me}).
     *
     * @see #resolveCurrentCustomer(CallerIdentity)
     */
    @Transactional
    public CustomerResponse getCurrentCustomer(CallerIdentity caller) {
        return customerMapper.toResponse(resolveCurrentCustomer(caller));
    }

    /**
     * Decide which customer an order is placed for (anti-IDOR, PRD-003 FR-10).
     * Staff must name the customer explicitly. Anyone else always orders for their own linked customer;
     * naming a different customer is rejected.
     *
     * @param caller              authenticated caller identity
     * @param requestedCustomerId customer ID supplied by the client, may be null
     * @return the customer ID the order must be placed for
     * @throws IllegalArgumentException  if a staff caller supplies no customer ID
     * @throws AccessDeniedException     if a non-staff caller names a customer other than their own
     * @throws ResourceNotFoundException if a non-staff caller has no linked customer
     */
    @Transactional
    public Long resolveOrderingCustomerId(CallerIdentity caller, Long requestedCustomerId) {
        if (caller != null && caller.staff()) {
            if (requestedCustomerId == null) {
                throw new IllegalArgumentException("Customer ID is required when placing an order on behalf of a customer.");
            }
            return requestedCustomerId;
        }
        Long ownCustomerId = resolveCurrentCustomer(caller).getId();
        if (requestedCustomerId != null && !requestedCustomerId.equals(ownCustomerId)) {
            throw new AccessDeniedException("Orders can only be placed for the customer account linked to the authenticated user.");
        }
        return ownCustomerId;
    }

    private Optional<Customer> linkByVerifiedEmail(CallerIdentity caller) {
        if (!caller.emailVerified() || caller.email() == null || caller.email().isBlank()) {
            return Optional.empty();
        }
        return customerRepository.findByEmail(caller.email().trim())
                .filter(customer -> {
                    if (customer.getAuthSubject() == null) {
                        return true;
                    }
                    log.warn("event=customer.link.rejected customer_id={} reason=already_linked_to_other_subject", customer.getId());
                    return false;
                })
                .map(customer -> {
                    customer.setAuthSubject(caller.subject());
                    customer.setUpdatedAt(Instant.now());
                    Customer linked = customerRepository.saveAndFlush(customer);
                    log.info("event=customer.linked customer_id={} method=verified_email", linked.getId());
                    return linked;
                });
    }

    /**
     * Update customer profile using optimistic concurrency control.
     * Guards against concurrent lost updates by comparing the expected version.
     *
     * @param id numeric customer ID
     * @param request update payload including optimistic locking version
     * @return updated CustomerResponse representation
     * @throws ResourceNotFoundException if customer does not exist
     * @throws ObjectOptimisticLockingFailureException if version does not match current state
     */
    @Transactional
    public CustomerResponse updateCustomer(Long id, UpdateCustomerRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + id));

        if (request.version() != null && !request.version().equals(customer.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Customer.class, id);
        }

        customerMapper.updateCustomerFromRequest(request, customer);
        customer.setUpdatedAt(Instant.now());

        Customer saved = customerRepository.saveAndFlush(customer);
        return customerMapper.toResponse(saved);
    }

    /**
     * Fuzzy name search: matches customers whose full_name contains the query
     * (case-insensitive substring). Results are ordered alphabetically.
     *
     * @param name  partial or full customer name, possibly with typos (substring match)
     * @param limit max results to return; clamped to [1, 20]
     * @return ranked list of matching customer summaries
     */
    @Transactional(readOnly = true)
    public List<CustomerSummaryResponse> searchByName(String name, int limit) {
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_SEARCH_LIMIT);
        return customerRepository
                .searchByNameFuzzy(name, PageRequest.of(0, effectiveLimit))
                .stream()
                .map(CustomerSummaryResponse::from)
                .toList();
    }
}
