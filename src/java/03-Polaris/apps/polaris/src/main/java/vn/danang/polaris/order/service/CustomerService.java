package vn.danang.polaris.order.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.order.dto.CustomerSummaryResponse;
import vn.danang.polaris.order.repository.CustomerRepository;

/**
 * Domain service for customer lookup operations within the order bounded context.
 */
@Service
public class CustomerService {

    private static final int MAX_SEARCH_LIMIT = 20;

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
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
