package vn.danang.polaris.assistant.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.security.UserContext;

class HttpPolarisMcpClientTest {

    private PolarisMcpProperties properties;
    private ObjectMapper objectMapper;
    private HttpClient mockHttpClient;
    private HttpResponse<String> mockHttpResponse;
    private HttpPolarisMcpClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new PolarisMcpProperties();
        properties.getCore().setUrl("http://localhost:8080/mcp");
        properties.getCore().setTimeoutSeconds(5);

        objectMapper = new ObjectMapper();
        mockHttpClient = mock(HttpClient.class);
        mockHttpResponse = (HttpResponse<String>) mock(HttpResponse.class);

        client = new HttpPolarisMcpClient(properties, objectMapper, mockHttpClient);
        SecurityContextHolder.clearContext();
    }

    @Test
    void testResolveEndpoint_normalizesLegacySseUrl() {
        properties.getCore().setUrl("http://localhost:8080/mcp/sse");
        assertEquals("http://localhost:8080/mcp", client.resolveEndpoint());

        properties.getCore().setUrl("http://localhost:8080/mcp");
        assertEquals("http://localhost:8080/mcp", client.resolveEndpoint());
    }

    @Test
    void testListAvailableTools_success() throws Exception {
        String jsonResponse = """
            {
              "jsonrpc": "2.0",
              "id": "1",
              "result": {
                "tools": [
                  {
                    "name": "search_available_products",
                    "description": "Search products in catalog",
                    "inputSchema": { "type": "object" }
                  }
                ]
              }
            }
            """;
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn(jsonResponse);
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        List<Tool> tools = client.listAvailableTools();

        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("search_available_products", tools.get(0).name());
        assertEquals("Search products in catalog", tools.get(0).description());
    }

    @Test
    void testListAvailableTools_httpError_returnsEmptyList() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(500);
        when(mockHttpResponse.body()).thenReturn("Internal Server Error");
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        List<Tool> tools = client.listAvailableTools();

        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testListAvailableTools_jsonRpcError_returnsEmptyList() throws Exception {
        String errorResponse = """
            {
              "jsonrpc": "2.0",
              "id": "1",
              "error": {
                "code": -32601,
                "message": "Method not found"
              }
            }
            """;
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn(errorResponse);
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        List<Tool> tools = client.listAvailableTools();

        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testCallTool_success() throws Exception {
        String jsonResponse = """
            {
              "jsonrpc": "2.0",
              "id": "call-1",
              "result": {
                "content": [
                  {
                    "type": "text",
                    "text": "Found 2 products matching query"
                  }
                ],
                "isError": false
              }
            }
            """;
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn(jsonResponse);
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        CallToolResult result = client.callTool("search_available_products", Map.of("query", "phone"));

        assertNotNull(result);
        assertFalse(result.isError());
        assertEquals(1, result.content().size());
        assertTrue(result.content().get(0) instanceof TextContent);
        assertEquals("Found 2 products matching query", ((TextContent) result.content().get(0)).text());
    }

    @Test
    void testCallTool_httpError_returnsErrorResult() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(503);
        when(mockHttpResponse.body()).thenReturn("Service Unavailable");
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        CallToolResult result = client.callTool("search_available_products", Map.of("query", "phone"));

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(((TextContent) result.content().get(0)).text().contains("HTTP 503"));
    }

    @Test
    void testCallTool_jsonRpcError_returnsErrorResult() throws Exception {
        String errorResponse = """
            {
              "jsonrpc": "2.0",
              "id": "call-1",
              "error": {
                "code": -32602,
                "message": "Invalid params"
              }
            }
            """;
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn(errorResponse);
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        CallToolResult result = client.callTool("search_available_products", Map.of("bad", 123));

        assertNotNull(result);
        assertTrue(result.isError());
        assertTrue(((TextContent) result.content().get(0)).text().contains("Invalid params"));
    }

    @Test
    void testBearerTokenInjectedInHttpRequest() throws Exception {
        Jwt jwt = Jwt.withTokenValue("ey-user-token-abc")
                .header("alg", "none")
                .claim("sub", "user-42")
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[]}}");
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        try {
            client.listAvailableTools();

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttpClient).send(captor.capture(), any());

            HttpRequest sentRequest = captor.getValue();
            assertTrue(sentRequest.headers().firstValue("Authorization").isPresent());
            assertEquals("Bearer ey-user-token-abc", sentRequest.headers().firstValue("Authorization").get());
            assertEquals("application/json", sentRequest.headers().firstValue("Content-Type").get());
            assertTrue(sentRequest.headers().firstValue("Accept").get().contains("application/json"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void testResolveBearerToken_withFallbackProperty() {
        properties.getCore().setAuthToken("fallback-token-xyz");
        HttpPolarisMcpClient clientWithFallback = new HttpPolarisMcpClient(properties, objectMapper, mockHttpClient);

        assertEquals("fallback-token-xyz", clientWithFallback.resolveBearerToken());
    }

    @Test
    void testResolveBearerToken_noAuth_returnsNull() {
        properties.getCore().setAuthToken(null);
        HttpPolarisMcpClient clientNoAuth = new HttpPolarisMcpClient(properties, objectMapper, mockHttpClient);

        assertNull(clientNoAuth.resolveBearerToken());
    }

    @Test
    void testOfflineHandling_handlesExceptionGracefully() {
        PolarisMcpProperties offlineProps = new PolarisMcpProperties();
        offlineProps.getCore().setUrl("http://127.0.0.1:59999/mcp");
        offlineProps.getCore().setTimeoutSeconds(1);

        HttpPolarisMcpClient realClient = new HttpPolarisMcpClient(offlineProps, objectMapper);

        List<Tool> tools = realClient.listAvailableTools();
        assertNotNull(tools);
        assertTrue(tools.isEmpty());

        CallToolResult result = realClient.callTool("search_available_products", Map.of("query", "test"));
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void testUserContext_delegation() {
        UserContext mockUserContext = mock(UserContext.class);
        when(mockUserContext.resolveBearerToken()).thenReturn("mocked-token-999");

        HttpPolarisMcpClient clientWithMockUserContext = new HttpPolarisMcpClient(properties, objectMapper, mockUserContext, mockHttpClient);
        assertEquals("mocked-token-999", clientWithMockUserContext.resolveBearerToken());
        verify(mockUserContext).resolveBearerToken();
    }

    @Test
    void testResetClient_doesNotThrow() {
        assertDoesNotThrow(() -> client.resetClient());
    }

    @Test
    void testCallTool_withTracer_injectsW3CTraceparentHeader() throws Exception {
        Tracer mockTracer = mock(Tracer.class);
        Span mockSpan = mock(Span.class);
        TraceContext mockContext = mock(TraceContext.class);

        when(mockTracer.currentSpan()).thenReturn(mockSpan);
        when(mockSpan.context()).thenReturn(mockContext);
        when(mockContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(mockContext.spanId()).thenReturn("00f067aa0ba902b7");
        when(mockContext.sampled()).thenReturn(true);

        HttpPolarisMcpClient tracedClient = new HttpPolarisMcpClient(
                properties, objectMapper, new UserContext(properties), mockHttpClient, mockTracer);

        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("""
            {
              "jsonrpc": "2.0",
              "id": "1",
              "result": {
                "content": [{"type": "text", "text": "Success"}],
                "isError": false
              }
            }
            """);
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        CallToolResult result = tracedClient.callTool("search_available_products", Map.of("query", "test"));
        assertNotNull(result);
        assertFalse(Boolean.TRUE.equals(result.isError()));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());

        HttpRequest sentRequest = captor.getValue();
        assertTrue(sentRequest.headers().firstValue("traceparent").isPresent());
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                sentRequest.headers().firstValue("traceparent").get());
    }

    @Test
    void testListAvailableTools_withTracer_injectsW3CTraceparentHeader() throws Exception {
        Tracer mockTracer = mock(Tracer.class);
        Span mockSpan = mock(Span.class);
        TraceContext mockContext = mock(TraceContext.class);

        when(mockTracer.currentSpan()).thenReturn(mockSpan);
        when(mockSpan.context()).thenReturn(mockContext);
        when(mockContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(mockContext.spanId()).thenReturn("00f067aa0ba902b7");
        when(mockContext.sampled()).thenReturn(false);

        HttpPolarisMcpClient tracedClient = new HttpPolarisMcpClient(
                properties, objectMapper, new UserContext(properties), mockHttpClient, mockTracer);

        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("""
            {
              "jsonrpc": "2.0",
              "id": "1",
              "result": {
                "tools": []
              }
            }
            """);
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        List<Tool> tools = tracedClient.listAvailableTools();
        assertNotNull(tools);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());

        HttpRequest sentRequest = captor.getValue();
        assertTrue(sentRequest.headers().firstValue("traceparent").isPresent());
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00",
                sentRequest.headers().firstValue("traceparent").get());
    }

    @Test
    void testCallTool_withoutTracer_doesNotInjectTraceparent() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("""
            {
              "jsonrpc": "2.0",
              "id": "1",
              "result": {
                "content": [{"type": "text", "text": "Success"}]
              }
            }
            """);
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        client.callTool("search_available_products", Map.of("query", "test"));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());

        HttpRequest sentRequest = captor.getValue();
        assertFalse(sentRequest.headers().firstValue("traceparent").isPresent());
    }

    @Test
    void testConstructor_withObjectProvider_extractsTracer() {
        Tracer mockTracer = mock(Tracer.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockTracer);

        HttpPolarisMcpClient clientFromProvider = new HttpPolarisMcpClient(
                properties, objectMapper, new UserContext(properties), provider);
        assertNotNull(clientFromProvider);
        verify(provider).getIfAvailable();
    }
}
