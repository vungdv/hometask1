package vn.danang.polaris.order.mapper;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.entity.Customer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CustomerMapper Unit Tests")
class CustomerMapperTest {

    private final CustomerMapper mapper = Mappers.getMapper(CustomerMapper.class);

    private Customer createSampleCustomer() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setFullName("Alice Tran");
        customer.setEmail("alice.tran@example.com");
        customer.setPhone("0901111111");
        customer.setCreatedAt(Instant.parse("2026-01-15T08:30:00Z"));
        return customer;
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Should automatically map identical properties from Customer to CustomerResponse")
        void shouldMapCustomerToCustomerResponse() {
            Customer customer = createSampleCustomer();

            CustomerResponse response = mapper.toResponse(customer);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.fullName()).isEqualTo("Alice Tran");
            assertThat(response.email()).isEqualTo("alice.tran@example.com");
            assertThat(response.phone()).isEqualTo("0901111111");
            assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-01-15T08:30:00Z"));
        }

        @Test
        @DisplayName("Should map list of Customer entities to CustomerResponse list preserving order")
        void shouldMapCustomerListToResponseList() {
            Customer c1 = createSampleCustomer();

            Customer c2 = new Customer();
            c2.setId(2L);
            c2.setFullName("Ben Nguyen");
            c2.setEmail("ben.nguyen@example.com");
            c2.setPhone("0902222222");
            c2.setCreatedAt(Instant.parse("2026-01-16T09:00:00Z"));

            List<CustomerResponse> responses = mapper.toResponseList(List.of(c1, c2));

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).id()).isEqualTo(1L);
            assertThat(responses.get(0).fullName()).isEqualTo("Alice Tran");
            assertThat(responses.get(1).id()).isEqualTo(2L);
            assertThat(responses.get(1).fullName()).isEqualTo("Ben Nguyen");
        }

        @Test
        @DisplayName("Should map CustomerResponse DTO back to Customer entity")
        void shouldMapCustomerResponseToCustomerEntity() {
            CustomerResponse response = new CustomerResponse(
                    3L,
                    "Chi Le",
                    "chi.le@example.com",
                    "0903333333",
                    Instant.parse("2026-02-01T10:00:00Z")
            );

            Customer entity = mapper.toEntity(response);

            assertThat(entity).isNotNull();
            assertThat(entity.getId()).isEqualTo(3L);
            assertThat(entity.getFullName()).isEqualTo("Chi Le");
            assertThat(entity.getEmail()).isEqualTo("chi.le@example.com");
            assertThat(entity.getPhone()).isEqualTo("0903333333");
            assertThat(entity.getCreatedAt()).isEqualTo(Instant.parse("2026-02-01T10:00:00Z"));
        }

        @Test
        @DisplayName("Should map via static CustomerResponse.from facade")
        void shouldMapViaCustomerResponseFromFacade() {
            Customer customer = createSampleCustomer();

            CustomerResponse response = CustomerResponse.from(customer);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.fullName()).isEqualTo("Alice Tran");
            assertThat(response.email()).isEqualTo("alice.tran@example.com");
        }
    }

    @Nested
    @DisplayName("2. Invalid / missing input")
    class InvalidInput {

        @Test
        @DisplayName("Should map Customer with null fields into CustomerResponse with null fields")
        void shouldMapCustomerWithNullFields() {
            Customer customer = new Customer();
            customer.setId(4L);
            customer.setFullName("No Contact");

            CustomerResponse response = mapper.toResponse(customer);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(4L);
            assertThat(response.fullName()).isEqualTo("No Contact");
            assertThat(response.email()).isNull();
            assertThat(response.phone()).isNull();
            assertThat(response.createdAt()).isNull();
        }

        @Test
        @DisplayName("Should map CustomerResponse with null fields into Customer entity with null fields")
        void shouldMapCustomerResponseWithNullFieldsToEntity() {
            CustomerResponse response = new CustomerResponse(5L, null, null, null, null);

            Customer entity = mapper.toEntity(response);

            assertThat(entity).isNotNull();
            assertThat(entity.getId()).isEqualTo(5L);
            assertThat(entity.getFullName()).isNull();
            assertThat(entity.getEmail()).isNull();
            assertThat(entity.getPhone()).isNull();
            assertThat(entity.getCreatedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should return null when source Customer is null")
        void shouldReturnNullWhenCustomerIsNull() {
            assertThat(mapper.toResponse(null)).isNull();
            assertThat(CustomerResponse.from(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when source CustomerResponse is null for toEntity")
        void shouldReturnNullWhenResponseIsNullForToEntity() {
            assertThat(mapper.toEntity(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when customers list is null")
        void shouldReturnNullWhenCustomersListIsNull() {
            assertThat(mapper.toResponseList(null)).isNull();
        }

        @Test
        @DisplayName("Should return empty list when customers list is empty")
        void shouldReturnEmptyListWhenCustomersListIsEmpty() {
            assertThat(mapper.toResponseList(Collections.emptyList())).isEmpty();
        }
    }
}
