package vn.danang.polaris.assistant.tools;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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

import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.assistant.tools.CatalogRestClient.CatalogProductView;

/**
 * Verifies {@link HttpCatalogRestClient} against a real HTTP server stubbed with WireMock — in
 * particular the 404-vs-everything-else distinction WO-020's acceptance criterion 5 depends on
 * (only a genuine 404 becomes an empty {@link Optional}; every other failure must propagate so the
 * caller fails closed instead of mistaking "Catalog is down" for "item is out of stock").
 */
class HttpCatalogRestClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String SKU = "NG-WATCH-01";
    private static final String PATH = "/api/v1/products/sku/" + SKU;

    private UserContext userContext;
    private HttpCatalogRestClient client;

    @BeforeEach
    void setUp() {
        userContext = mock(UserContext.class);
        PolarisCoreRestProperties properties = new PolarisCoreRestProperties();
        properties.setBaseUrl(wireMock.baseUrl());
        client = new HttpCatalogRestClient(
                org.springframework.web.client.RestClient.builder().baseUrl(properties.getBaseUrl()).build(),
                userContext);
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given Catalog returns 200 with a product, when queried, then the response is parsed into a CatalogProductView")
        void getBySku_whenFound_returnsProductView() {
            wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                            {"id":2,"sku":"NG-WATCH-01","name":"Nova Smart Watch","description":"d","category":"Wearables",
                             "price":89.90,"stockQuantity":60,"isAvailable":true,"active":true,"createdAt":"2026-01-01T00:00:00Z"}
                            """)));

            Optional<CatalogProductView> result = client.getBySku(SKU);

            assertThat(result).isPresent();
            assertThat(result.get().sku()).isEqualTo(SKU);
            assertThat(result.get().stockQuantity()).isEqualTo(60);
            assertThat(result.get().price()).isEqualByComparingTo("89.90");
        }

        @Test
        @DisplayName("Given the caller has a Bearer token, when queried, then it is relayed on the outbound request")
        void getBySku_relaysCallersBearerToken() {
            when(userContext.resolveBearerToken()).thenReturn("caller-jwt-token");
            wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"sku\":\"NG-WATCH-01\",\"name\":\"Watch\",\"price\":10.0,\"stockQuantity\":1,\"isAvailable\":true}")));

            client.getBySku(SKU);

            wireMock.verify(getRequestedFor(urlEqualTo(PATH))
                    .withHeader("Authorization", equalTo("Bearer caller-jwt-token")));
        }
    }

    // =========================================================================
    // 2. Invalid input / distinct failure modes
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & failure handling")
    class FailureHandling {

        @Test
        @DisplayName("Given Catalog returns 404 (SKU doesn't exist), when queried, then an empty Optional is returned")
        void getBySku_whenNotFound_returnsEmpty() {
            wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

            assertThat(client.getBySku(SKU)).isEmpty();
        }

        @Test
        @DisplayName("Given Catalog returns 403 (e.g. missing catalog.read), when queried, then the exception propagates rather than being treated as out-of-stock")
        void getBySku_whenForbidden_propagatesException() {
            wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(403)));

            assertThatThrownBy(() -> client.getBySku(SKU)).isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("Given Catalog returns 500, when queried, then the exception propagates so the caller fails closed")
        void getBySku_whenServerError_propagatesException() {
            wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> client.getBySku(SKU)).isInstanceOf(HttpServerErrorException.class);
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given no Bearer token is resolvable, when queried, then no Authorization header is sent")
        void getBySku_withNoToken_omitsAuthorizationHeader() {
            when(userContext.resolveBearerToken()).thenReturn(null);
            wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"sku\":\"NG-WATCH-01\",\"name\":\"Watch\",\"price\":10.0,\"stockQuantity\":1,\"isAvailable\":true}")));

            client.getBySku(SKU);

            wireMock.verify(getRequestedFor(urlEqualTo(PATH)).withoutHeader("Authorization"));
        }
    }
}
