package vn.danang.polaris.mcp;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Spring configuration wiring the native Model Context Protocol (MCP) server,
 * HTTP SSE transport servlet, and tool facades into the Polaris runtime.
 */
@Configuration
public class McpServerConfig {

    public static final String MESSAGE_ENDPOINT = "/mcp/message";
    public static final String SSE_ENDPOINT = "/mcp/sse";

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public JacksonMcpJsonMapper jacksonMcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public HttpServletSseServerTransportProvider httpServletSseServerTransportProvider(JacksonMcpJsonMapper jsonMapper) {
        return HttpServletSseServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .messageEndpoint(MESSAGE_ENDPOINT)
                .sseEndpoint(SSE_ENDPOINT)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpServletRegistrationBean(
            HttpServletSseServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletSseServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, SSE_ENDPOINT, MESSAGE_ENDPOINT);
        registration.setAsyncSupported(true);
        registration.setName("mcpSseServlet");
        return registration;
    }

    @Bean
    public McpSyncServer mcpSyncServer(
            HttpServletSseServerTransportProvider transport,
            ProductMcpTools productMcpTools,
            OrderMcpTools orderMcpTools,
            JacksonMcpJsonMapper jsonMapper) {
        return McpServer.sync(transport)
                .serverInfo("polaris-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(productMcpTools.getSearchProductsTool(jsonMapper), (exchange, request) -> productMcpTools.searchAvailableProducts(request.arguments()))
                .toolCall(productMcpTools.getProductBySkuTool(jsonMapper), (exchange, request) -> productMcpTools.getProductBySku(request.arguments()))
                .toolCall(orderMcpTools.getOrderStatusTool(jsonMapper), (exchange, request) -> orderMcpTools.getOrderStatus(request.arguments()))
                .toolCall(orderMcpTools.getOrderDetailsTool(jsonMapper), (exchange, request) -> orderMcpTools.getOrderDetails(request.arguments()))
                .toolCall(orderMcpTools.getPlaceOrderTool(jsonMapper), (exchange, request) -> orderMcpTools.placeOrder(request.arguments()))
                .toolCall(orderMcpTools.getListCustomerOrdersTool(jsonMapper), (exchange, request) -> orderMcpTools.listCustomerOrders(request.arguments()))
                .toolCall(orderMcpTools.getCancelOrderTool(jsonMapper), (exchange, request) -> orderMcpTools.cancelOrder(request.arguments()))
                .build();
    }
}
