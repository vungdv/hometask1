package vn.danang.polaris.assistant.customer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Nullable;

/**
 * {@link CurrentCustomerClient} over Order Management's REST contract {@code GET /api/v1/customers/me},
 * authenticated with the caller's own bearer token and carrying W3C {@code traceparent}.
 */
@Component
public class HttpCurrentCustomerClient implements CurrentCustomerClient {

    static final String CURRENT_CUSTOMER_PATH = "/api/v1/customers/me";

    private static final Logger log = LoggerFactory.getLogger(HttpCurrentCustomerClient.class);

    private final PolarisCoreApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public HttpCurrentCustomerClient(PolarisCoreApiProperties properties, ObjectMapper objectMapper, ObjectProvider<Tracer> tracerProvider) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build(), tracerProvider != null ? tracerProvider.getIfAvailable() : null);
    }

    public HttpCurrentCustomerClient(PolarisCoreApiProperties properties, ObjectMapper objectMapper, HttpClient httpClient, @Nullable Tracer tracer) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.tracer = tracer;
    }

    @Override
    public Optional<Long> findCurrentCustomerId(String callerBearerToken) {
        if (callerBearerToken == null || callerBearerToken.isBlank()) {
            throw new CustomerLookupException("A caller access token is required to resolve the current customer.");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + CURRENT_CUSTOMER_PATH))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + callerBearerToken)
                .GET();
        injectTraceParent(builder);

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomerLookupException("Interrupted while resolving the current customer.", e);
        } catch (Exception e) {
            log.error("Current customer lookup failed: error={}", e.getMessage());
            throw new CustomerLookupException("Order Management is unreachable: " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status == 404) {
            log.info("No customer linked to the caller: status=404");
            return Optional.empty();
        }
        if (status != 200) {
            log.warn("Current customer lookup rejected: status={}", status);
            throw new CustomerLookupException("Order Management rejected the customer lookup with HTTP " + status + ".");
        }
        try {
            JsonNode id = objectMapper.readTree(response.body()).get("id");
            if (id == null || !id.canConvertToLong()) {
                throw new CustomerLookupException("Order Management returned a customer without an id.");
            }
            return Optional.of(id.asLong());
        } catch (CustomerLookupException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomerLookupException("Order Management returned an unreadable customer payload.", e);
        }
    }

    private void injectTraceParent(HttpRequest.Builder builder) {
        if (tracer == null) {
            return;
        }
        Span currentSpan = tracer.currentSpan();
        TraceContext context = currentSpan != null ? currentSpan.context()
                : (tracer.currentTraceContext() != null ? tracer.currentTraceContext().context() : null);
        if (context != null && context.traceId() != null && context.spanId() != null) {
            String sampled = (context.sampled() != null && !context.sampled()) ? "00" : "01";
            builder.header("traceparent", "00-" + context.traceId() + "-" + context.spanId() + "-" + sampled);
        }
    }
}
