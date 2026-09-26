package vn.danang.polaris.assistant.tools;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import vn.danang.polaris.assistant.security.UserContext;

/**
 * {@link CatalogRestClient} implementation backed by Spring's {@link RestClient}, calling
 * Catalog's real HTTP endpoint (never Catalog's/Order's internal services in-process).
 *
 * <p>Relays the caller's own Bearer token via {@link UserContext#resolveBearerToken()} — the same
 * token-propagation convention {@code HttpPolarisMcpClient} already uses — rather than a service
 * credential, per ADR-0004 §2's zero-privilege-escalation invariant. See WO-020's "Named gap —
 * authorization": this is exactly why the {@code purchase-management} realm role needed
 * {@code catalog.read} granted (Task 10, in {@code docker/keycloak/realm-export.json}).
 *
 * <p><b>404 vs. everything else:</b> only a genuine 404 (SKU doesn't exist) maps to an empty
 * {@link Optional} — that's the one case {@code DraftStagingService} treats as "0 available"
 * (Task 4). Every other failure (403 from a missing permission, 5xx, connection refused, timeout)
 * is let through as a {@link org.springframework.web.client.RestClientException} so the caller
 * fails closed (a tool error, per this WO's acceptance criterion 5) instead of silently being
 * mistaken for an out-of-stock item.
 */
@Component
public class HttpCatalogRestClient implements CatalogRestClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCatalogRestClient.class);

    private final RestClient restClient;
    private final UserContext userContext;

    @Autowired
    public HttpCatalogRestClient(PolarisCoreRestProperties properties, UserContext userContext) {
        this(RestClient.builder().baseUrl(properties.getBaseUrl()).build(), userContext);
    }

    public HttpCatalogRestClient(RestClient restClient, UserContext userContext) {
        this.restClient = restClient;
        this.userContext = userContext;
    }

    @Override
    public Optional<CatalogProductView> getBySku(String sku) {
        try {
            CatalogProductView product = restClient.get()
                    .uri("/api/v1/products/sku/{sku}", sku)
                    .headers(headers -> {
                        String token = userContext != null ? userContext.resolveBearerToken() : null;
                        if (token != null && !token.isBlank()) {
                            headers.setBearerAuth(token);
                        }
                    })
                    .retrieve()
                    .body(CatalogProductView.class);
            return Optional.ofNullable(product);
        } catch (HttpClientErrorException.NotFound notFound) {
            log.info("Catalog reported no product for SKU '{}'", sku);
            return Optional.empty();
        }
    }
}
