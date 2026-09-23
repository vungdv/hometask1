package vn.danang.polaris.order.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerSearchApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/v1/customers/search?name=Alice returns Alice Tran from seed data")
    void search_byFirstName_returnsMatch() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "Alice")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].full_name").value("Alice Tran"))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].email").exists());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search?name=nguyen returns Ben Nguyen from seed data")
    void search_byLastName_caseInsensitive() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "nguyen")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].full_name").value("Ben Nguyen"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/search?name=nomatch returns empty list")
    void search_noMatch_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "zzznomatch")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search without name parameter returns 400")
    void search_missingName_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search unauthenticated returns 401")
    void search_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "Alice"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search with limit=1 returns at most 1 result")
    void search_withLimit_enforcesMax() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "e")   // likely to match many
                        .param("limit", "1")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("GET /api/v1/customers/1 returns 200 with Alice Tran and identical CustomerResponse mapped fields")
    void getCustomerById_success_returnsCustomerWithAllFields() throws Exception {
        mockMvc.perform(get("/api/v1/customers/1")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.fullName").value("Alice Tran"))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.lastName").value("Tran"))
                .andExpect(jsonPath("$.email").value("alice.tran@example.com"))
                .andExpect(jsonPath("$.secondaryEmail").value("alice.personal@example.com"))
                .andExpect(jsonPath("$.phone").value("0901111111"))
                .andExpect(jsonPath("$.company").value("Danang Tech Solutions"))
                .andExpect(jsonPath("$.customerTier").value("GOLD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.billingCity").value("Da Nang"))
                .andExpect(jsonPath("$.shippingCity").value("Da Nang"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("GET /api/v1/customers/999 returns 404 ProblemDetail when customer not found")
    void getCustomerById_notFound_returns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/customers/999")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Customer not found with id: 999"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/1 unauthenticated returns 401")
    void getCustomerById_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/customers/1"))
                .andExpect(status().isUnauthorized());
    }
}
