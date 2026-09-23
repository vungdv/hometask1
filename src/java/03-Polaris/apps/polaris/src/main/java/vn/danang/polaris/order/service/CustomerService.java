package vn.danang.polaris.order.service;

import java.util.List;

import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.dto.CustomerSummaryResponse;
import vn.danang.polaris.order.mapper.CustomerMapper;
import vn.danang.polaris.order.repository.CustomerRepository;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

/**
 * Domain service for customer lookup operations within the order bounded context.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

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
