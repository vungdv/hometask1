package vn.danang.polaris.assistant.tools;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.dto.OrderItemRequest;
import vn.danang.polaris.assistant.security.UserContext;

/**
 * {@link OrderRestClient} implementation backed by Spring's {@link RestClient}, calling Order's
 * real HTTP endpoint (never {@code OrderService} in-process). Mirrors {@link HttpCatalogRestClient}:
 * same {@code RestClient} + {@link UserContext#resolveBearerToken()} token-relay pattern, same
 * {@code polaris.core.rest.base-url} property (WO-020's, not a second one for the same host).
 */
@Component
public class HttpOrderRestClient implements OrderRestClient {

    private final RestClient restClient;
    private final UserContext userContext;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpOrderRestClient(PolarisCoreRestProperties properties, UserContext userContext, ObjectMapper objectMapper) {
        this(RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(http1RequestFactory())
                .build(), userContext, objectMapper);
    }

    /**
     * Pins HTTP/1.1: the JDK HttpClient's default HTTP/2-with-fallback negotiation can reset a
     * POST-with-body against a plain HTTP/1.1 server (observed as "RST_STREAM: Stream cancelled"
     * against WireMock in tests) - Polaris Core's own server is HTTP/1.1 only too.
     */
    private static JdkClientHttpRequestFactory http1RequestFactory() {
        return new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build());
    }

    public HttpOrderRestClient(RestClient restClient, UserContext userContext, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.userContext = userContext;
        this.objectMapper = objectMapper;
    }

    @Override
    public OrderPlacedView placeOrder(Long customerId, List<OrderItemRequest> items, String idempotencyKey) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("customerId", customerId);
        requestBody.put("items", items.stream()
                .map(item -> Map.of("sku", item.sku(), "quantity", item.quantity()))
                .toList());

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        String token = userContext != null ? userContext.resolveBearerToken() : null;
                        if (token != null && !token.isBlank()) {
                            headers.setBearerAuth(token);
                        }
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                            headers.set("Idempotency-Key", idempotencyKey);
                        }
                    })
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);

            String rawBody = response.getBody();
            JsonNode node = objectMapper.readTree(rawBody);
            String orderNumber = node.path("orderNumber").asText(null);
            String status = node.path("status").asText(null);
            BigDecimal totalAmount = node.hasNonNull("totalAmount") ? new BigDecimal(node.get("totalAmount").asText()) : null;
            return new OrderPlacedView(orderNumber, status, totalAmount, rawBody);
        } catch (RestClientResponseException ex) {
            throw new OrderPlacementRejectedException(ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to parse Order response body", ex);
        }
    }
}
