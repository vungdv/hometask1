package vn.danang.polaris.assistant.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Getter;
import lombok.Setter;

@ConfigurationProperties(prefix = "polaris.mcp")
@Getter
@Setter
public class PolarisMcpProperties {

    private CoreMcp core = new CoreMcp();

    @Getter
    @Setter
    public static class CoreMcp {
        private String url = "http://localhost:8080/mcp/sse";
        private String messageEndpoint = "http://localhost:8080/mcp/message";
        private boolean enabled = true;
        private int timeoutSeconds = 10;
    }
}
