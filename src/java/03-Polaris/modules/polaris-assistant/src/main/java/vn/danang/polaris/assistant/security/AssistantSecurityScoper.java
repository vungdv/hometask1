package vn.danang.polaris.assistant.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.model.SessionContext;
import vn.danang.polaris.entity.Order;
import vn.danang.polaris.repository.CustomerRepository;

@Component
public class AssistantSecurityScoper {

    private final CustomerRepository customerRepository;

    public AssistantSecurityScoper(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public boolean isStaffOrAdmin(Authentication authentication) {
        if (authentication == null) return false;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String role = authority.getAuthority();
            if ("ROLE_STAFF".equalsIgnoreCase(role) || "ROLE_ADMIN".equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    public boolean isStaffOrAdmin(SessionContext context) {
        return context != null && context.isStaff();
    }

    public boolean canAccessOrder(Order order, SessionContext context) {
        if (order == null) return true;
        if (context == null) return false;
        if (context.isStaff()) return true;

        if (order.getCustomer() == null || context.customerId() == null) {
            return false;
        }

        return order.getCustomer().getId().equals(context.customerId());
    }

    public boolean canActAsCustomer(Long targetCustomerId, SessionContext context) {
        if (targetCustomerId == null) return true;
        if (context == null) return false;
        if (context.isStaff()) return true;

        if (context.customerId() == null) return false;
        return targetCustomerId.equals(context.customerId());
    }

    public boolean customerExists(Long customerId) {
        if (customerId == null) return false;
        if (customerRepository == null) return true;
        return customerRepository.existsById(customerId);
    }
}
