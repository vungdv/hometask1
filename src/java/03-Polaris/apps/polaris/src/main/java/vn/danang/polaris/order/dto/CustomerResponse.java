package vn.danang.polaris.order.dto;

import java.time.Instant;

import org.mapstruct.factory.Mappers;

import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.mapper.CustomerMapper;

/**
 * Customer response DTO containing complete customer profile details.
 * <p>
 * Demonstrates MapStruct's most compelling and useful sweet spot:
 * Because all properties in this DTO are identical in name and type to {@link Customer} entity,
 * MapStruct automatically maps every field at compile time with zero configuration annotations.
 */
public record CustomerResponse(
    Long id,
    String fullName,
    String email,
    String phone,
    Instant createdAt
) {
    private static final CustomerMapper MAPPER = Mappers.getMapper(CustomerMapper.class);

    public static CustomerResponse from(Customer customer) {
        return MAPPER.toResponse(customer);
    }
}
