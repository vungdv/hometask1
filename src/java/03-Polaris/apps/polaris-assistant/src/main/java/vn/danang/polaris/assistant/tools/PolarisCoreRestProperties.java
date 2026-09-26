package vn.danang.polaris.assistant.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Base URL for calling Polaris Core's published REST contracts directly (as opposed to the MCP
 * JSON-RPC surface {@link PolarisMcpProperties} points at). First consumer:
 * {@link CatalogRestClient}'s read-only {@code GET /api/v1/products/sku/{sku}} call.
 */
@ConfigurationProperties(prefix = "polaris.core.rest")
@Getter
@Setter
public class PolarisCoreRestProperties {

    private String baseUrl = "http://localhost:8080";
}
