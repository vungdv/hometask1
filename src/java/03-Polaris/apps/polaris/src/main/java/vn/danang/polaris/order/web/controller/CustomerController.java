package vn.danang.polaris.order.web.controller;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.dto.CustomerSummaryResponse;
import vn.danang.polaris.order.service.CustomerService;

/**
 * REST controller for customer lookup operations.
 * Bounded context: order domain (customers are referenced by orders).
 */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Customer lookup and fuzzy name search for order placement")
public class CustomerController {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/search")
    @Operation(
        summary = "Fuzzy customer name search",
        description = "Search customers by partial or misspelled name (first name, last name, or full name). "
            + "Uses case-insensitive substring matching. Returns ranked candidates for operator confirmation before placing an order."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Matching customers returned (may be empty list if no match found)",
            content = @Content(
                mediaType = "application/json",
                array = @ArraySchema(schema = @Schema(implementation = CustomerSummaryResponse.class))
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Missing, blank name parameter or invalid limit",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token")
    })
    public ResponseEntity<List<CustomerSummaryResponse>> searchByName(
            @Parameter(description = "Customer name to search for (partial, first/last name only, or with typos)", required = true)
            @RequestParam(required = false) String name,
            @Parameter(description = "Maximum results to return (1–20, default 5)")
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'name' is required and must not be blank.");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Query parameter 'limit' must be between 1 and " + MAX_LIMIT + ".");
        }

        List<CustomerSummaryResponse> results = customerService.searchByName(name.trim(), limit);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Get customer by ID",
        description = "Retrieves full customer profile details by internal numeric ID."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Customer profile retrieved successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CustomerResponse.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Customer not found with ID",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token")
    })
    public ResponseEntity<CustomerResponse> getCustomerById(
            @Parameter(description = "Customer internal numeric ID", required = true)
            @PathVariable Long id) {
        CustomerResponse customer = customerService.getCustomerById(id);
        return ResponseEntity.ok(customer);
    }
}
