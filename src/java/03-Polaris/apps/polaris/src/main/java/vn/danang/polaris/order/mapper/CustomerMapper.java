package vn.danang.polaris.order.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.entity.Customer;

/**
 * MapStruct mapper between {@link Customer} entity and {@link CustomerResponse} DTO.
 * <p>
 * Demonstrates MapStruct's most compelling and useful capability:
 * when the target DTO properties match the source entity properties identically in name and type,
 * MapStruct automatically maps every property at compile time without requiring any manual
 * {@code @Mapping} annotations or reflective runtime overhead.
 */
@Mapper(componentModel = "spring")
public interface CustomerMapper {

    CustomerMapper INSTANCE = Mappers.getMapper(CustomerMapper.class);

    CustomerResponse toResponse(Customer customer);

    List<CustomerResponse> toResponseList(List<Customer> customers);

    Customer toEntity(CustomerResponse response);
}
