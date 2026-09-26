package vn.danang.polaris.assistant.tools;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.dto.OrderItemRequest;
import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.assistant.tools.OrderRestClient.OrderPlacedView;

/**
 * Verifies {@link HttpOrderRestClient} against a real HTTP server stubbed with WireMock — in
 * particular that a non-2xx response becomes an {@link OrderPlacementRejectedException} carrying
 * the exact downstream status/body, since the confirm endpoint proxies it as-is (WO-021 Task 5).
 */
class HttpOrderRestClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String PATH = "/api/v1/orders";

    private UserContext userContext;
    private HttpOrderRestClient client;

    @BeforeEach
    void setUp() {
        userContext = mock(UserContext.class);
        // Pin HTTP/1.1 - WireMock's plain HTTP/1.1 server resets a POST-with-body under the JDK
        // HttpClient's default HTTP/2-with-fallback negotiation (see HttpOrderRestClient).
        java.net.http.HttpClient http1Client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        client = new HttpOrderRestClient(
                org.springframework.web.client.RestClient.builder()
                        .baseUrl(wireMock.baseUrl())
                        .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(http1Client))
                        .build(),
                userContext, new ObjectMapper());
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given Order returns 201 with an OrderResponse, when placed, then the parsed view carries orderNumber/status/totalAmount and the raw body verbatim")
        void placeOrder_created_parsesViewAndKeepsRawBody() {
            String responseJson = "{\"orderNumber\":\"ORD-1001\",\"status\":\"PLACED\",\"totalAmount\":179.80}";
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(responseJson)));

            OrderPlacedView view = client.placeOrder(42L, List.of(new OrderItemRequest("NG-WATCH-01", 2)), "idem-key-1");

            assertThat(view.orderNumber()).isEqualTo("ORD-1001");
            assertThat(view.status()).isEqualTo("PLACED");
            assertThat(view.totalAmount()).isEqualByComparingTo("179.80");
            assertThat(view.rawResponseBody()).isEqualTo(responseJson);
        }

        @Test
        @DisplayName("Given an Idempotency-Key and a Bearer token, when placed, then both are relayed on the outbound request")
        void placeOrder_relaysIdempotencyKeyAndBearerToken() {
            when(userContext.resolveBearerToken()).thenReturn("caller-jwt-token");
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"orderNumber\":\"ORD-1001\",\"status\":\"PLACED\",\"totalAmount\":10.0}")));

            client.placeOrder(42L, List.of(new OrderItemRequest("NG-WATCH-01", 1)), "idem-key-1");

            wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                    .withHeader("Authorization", equalTo("Bearer caller-jwt-token"))
                    .withHeader("Idempotency-Key", equalTo("idem-key-1"))
                    .withRequestBody(equalToJson("{\"customerId\":42,\"items\":[{\"sku\":\"NG-WATCH-01\",\"quantity\":1}]}", true, true)));
        }
    }

    // =========================================================================
    // 2. Invalid input / failure handling
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & protocol error handling")
    class FailureHandling {

        @Test
        @DisplayName("Given Order returns 400 (e.g. insufficient stock), when placed, then OrderPlacementRejectedException carries the exact status and body")
        void placeOrder_badRequest_throwsRejectedExceptionWithBody() {
            String problemJson = "{\"title\":\"Insufficient Stock\",\"status\":400}";
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(problemJson)));

            assertThatThrownBy(() -> client.placeOrder(42L, List.of(new OrderItemRequest("NG-WATCH-01", 1)), "idem-key-1"))
                    .isInstanceOf(OrderPlacementRejectedException.class)
                    .satisfies(ex -> {
                        OrderPlacementRejectedException rejected = (OrderPlacementRejectedException) ex;
                        assertThat(rejected.getStatusCode()).isEqualTo(400);
                        assertThat(rejected.getResponseBody()).isEqualTo(problemJson);
                    });
        }

        @Test
        @DisplayName("Given Order returns 404 (customer not found), when placed, then OrderPlacementRejectedException carries status 404")
        void placeOrder_notFound_throwsRejectedExceptionWith404() {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"title\":\"Resource Not Found\"}")));

            assertThatThrownBy(() -> client.placeOrder(999L, List.of(new OrderItemRequest("NG-WATCH-01", 1)), "idem-key-1"))
                    .isInstanceOf(OrderPlacementRejectedException.class)
                    .satisfies(ex -> assertThat(((OrderPlacementRejectedException) ex).getStatusCode()).isEqualTo(404));
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given Order returns 500, when placed, then OrderPlacementRejectedException carries status 500")
        void placeOrder_serverError_throwsRejectedExceptionWith500() {
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> client.placeOrder(42L, List.of(new OrderItemRequest("NG-WATCH-01", 1)), "idem-key-1"))
                    .isInstanceOf(OrderPlacementRejectedException.class)
                    .satisfies(ex -> assertThat(((OrderPlacementRejectedException) ex).getStatusCode()).isEqualTo(500));
        }
    }
}
