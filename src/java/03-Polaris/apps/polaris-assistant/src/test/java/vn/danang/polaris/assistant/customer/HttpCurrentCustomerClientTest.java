package vn.danang.polaris.assistant.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link HttpCurrentCustomerClient}: calls Order Management's {@code GET /api/v1/customers/me}
 * with the caller's own token and maps 200 / 404 / other statuses.
 */
class HttpCurrentCustomerClientTest {

    private HttpClient httpClient;
    private HttpResponse<String> response;
    private HttpCurrentCustomerClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PolarisCoreApiProperties properties = new PolarisCoreApiProperties();
        properties.setBaseUrl("http://polaris:8080/");
        httpClient = mock(HttpClient.class);
        response = (HttpResponse<String>) mock(HttpResponse.class);
        client = new HttpCurrentCustomerClient(properties, new ObjectMapper(), httpClient, null);
    }

    @Test
    @DisplayName("Given a linked customer, when resolved, then GETs /api/v1/customers/me with the caller's bearer token and returns its id")
    void resolves_linked_customer() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"id\":7,\"fullName\":\"Alice Tran\"}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThat(client.findCurrentCustomerId("user-token")).contains(7L);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), any());
        assertThat(request.getValue().uri().toString()).isEqualTo("http://polaris:8080/api/v1/customers/me");
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer user-token");
    }

    @Test
    @DisplayName("Given no linked customer (404), when resolved, then empty")
    void not_linked_is_empty() throws Exception {
        when(response.statusCode()).thenReturn(404);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThat(client.findCurrentCustomerId("user-token")).isEmpty();
    }

    @Test
    @DisplayName("Given a rejected token (401), when resolved, then CustomerLookupException")
    void rejected_token_fails() throws Exception {
        when(response.statusCode()).thenReturn(401);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.findCurrentCustomerId("user-token"))
                .isInstanceOf(CustomerLookupException.class).hasMessageContaining("401");
    }

    @Test
    @DisplayName("Given Order Management is unreachable, when resolved, then CustomerLookupException")
    void unreachable_fails() throws Exception {
        doThrow(new IOException("connection refused")).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.findCurrentCustomerId("user-token"))
                .isInstanceOf(CustomerLookupException.class).hasMessageContaining("connection refused");
    }

    @Test
    @DisplayName("Given a payload without id, when resolved, then CustomerLookupException")
    void payload_without_id_fails() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"fullName\":\"Alice\"}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.findCurrentCustomerId("user-token")).isInstanceOf(CustomerLookupException.class);
    }

    @Test
    @DisplayName("Given no caller token, when resolved, then refused without any request")
    void blank_token_is_refused() {
        assertThatThrownBy(() -> client.findCurrentCustomerId(" ")).isInstanceOf(CustomerLookupException.class);
        verifyNoInteractions(httpClient);
    }
}
