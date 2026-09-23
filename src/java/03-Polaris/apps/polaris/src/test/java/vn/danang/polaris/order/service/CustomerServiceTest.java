package vn.danang.polaris.order.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.dto.CustomerSummaryResponse;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.mapper.CustomerMapper;
import vn.danang.polaris.order.repository.CustomerRepository;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerService Unit Tests")
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    private final CustomerMapper customerMapper = Mappers.getMapper(CustomerMapper.class);

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerRepository, customerMapper);
    }

    private Customer createSampleCustomer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFullName(name);
        customer.setEmail(name.toLowerCase().replace(" ", ".") + "@example.com");
        customer.setPhone("0901234567");
        customer.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        return customer;
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("getCustomerById should return mapped CustomerResponse when customer is found")
        void getCustomerById_found_returnsMappedCustomerResponse() {
            Customer customer = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

            CustomerResponse response = customerService.getCustomerById(1L);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.fullName()).isEqualTo("Alice Tran");
            assertThat(response.email()).isEqualTo("alice.tran@example.com");
            assertThat(response.phone()).isEqualTo("0901234567");
            assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));

            verify(customerRepository).findById(1L);
        }

        @Test
        @DisplayName("searchByName should return mapped customer summaries")
        void searchByName_validName_returnsMappedSummaries() {
            Customer c1 = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.searchByNameFuzzy(eq("Alice"), any(Pageable.class)))
                    .thenReturn(List.of(c1));

            List<CustomerSummaryResponse> summaries = customerService.searchByName("Alice", 5);

            assertThat(summaries).hasSize(1);
            assertThat(summaries.get(0).id()).isEqualTo(1L);
            assertThat(summaries.get(0).fullName()).isEqualTo("Alice Tran");
        }
    }

    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("getCustomerById should throw ResourceNotFoundException when customer ID does not exist")
        void getCustomerById_notFound_throwsResourceNotFoundException() {
            when(customerRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.getCustomerById(999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Customer not found with id: 999");

            verify(customerRepository).findById(999L);
        }
    }

    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("getCustomerById should map gracefully when optional entity fields are null")
        void getCustomerById_withNullOptionalFields_mapsGracefully() {
            Customer customer = new Customer();
            customer.setId(10L);
            customer.setFullName("Minimal Customer");
            when(customerRepository.findById(10L)).thenReturn(Optional.of(customer));

            CustomerResponse response = customerService.getCustomerById(10L);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(10L);
            assertThat(response.fullName()).isEqualTo("Minimal Customer");
            assertThat(response.email()).isNull();
            assertThat(response.phone()).isNull();
            assertThat(response.createdAt()).isNull();
        }
    }
}
