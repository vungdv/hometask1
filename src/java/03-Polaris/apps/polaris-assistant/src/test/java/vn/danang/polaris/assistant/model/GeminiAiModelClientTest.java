package vn.danang.polaris.assistant.model;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

class GeminiAiModelClientTest {

    private AssistantAiProperties properties;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private GeminiAiModelClient client;

    @BeforeEach
    void setUp() {
        properties = new AssistantAiProperties();
        httpClient = mock(HttpClient.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        client = new GeminiAiModelClient(properties, httpClient, objectMapper);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when apiKey is null")
    void chat_whenApiKeyIsNull_throwsIllegalArgumentException() throws Exception {
        properties.setApiKey(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
          () -> client.chat(List.of(createUserMessage("Hello Polaris!"))));

        assertThat(exception).hasMessage("Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini.");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("Should return local assistant fallback when apiKey is blank")
    void chat_whenApiKeyIsBlank_returnsFallback() throws Exception {
        properties.setApiKey("   ");

        String reply = client.chat(List.of(createUserMessage("Hello Polaris!")));

        assertThat(reply).contains("Live AI Model key is not configured");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("Should return local assistant fallback when apiKey is unresolved placeholder '${GEMINI_API_KEY}'")
    void chat_whenApiKeyIsUnresolvedPlaceholder_returnsFallbackWithoutUriSyntaxError() throws Exception {
        properties.setApiKey("${GEMINI_API_KEY}");

        String reply = client.chat(List.of(createUserMessage("Hello Polaris!")));

        assertThat(reply).contains("Live AI Model key is not configured");
        assertThat(reply).contains("Echo: \"Hello Polaris!\"");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("Should return local assistant fallback when apiKey is unresolved placeholder and message list is empty")
    void chat_whenApiKeyIsUnresolvedPlaceholderAndEmptyMessages_returnsFallbackWithEmptyEcho() throws Exception {
        properties.setApiKey("${GEMINI_API_KEY}");

        String reply = client.chat(List.of());

        assertThat(reply).contains("Live AI Model key is not configured");
        assertThat(reply).contains("Echo: \"\"");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("Should send x-goog-api-key header and return model reply when configured with valid key")
    @SuppressWarnings("unchecked")
    void chat_whenApiKeyIsValid_sendsHeaderAndReturnsReply() throws Exception {
        // Arrange
        properties.setApiKey("test-valid-api-key-12345");
        properties.setModel("gemini-3.6-flash");

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "Hello! How can I help you today?"
                          }
                        ],
                        "role": "model"
                      }
                    }
                  ]
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        AssistantMessage userMessage = createUserMessage("Hello Polaris!");

        // Act
        String reply = client.chat(List.of(userMessage));

        // Assert
        assertThat(reply).isEqualTo("Hello! How can I help you today?");

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());

        HttpRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.uri().toString())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent");
        assertThat(capturedRequest.headers().firstValue("x-goog-api-key"))
                .hasValue("test-valid-api-key-12345");
        assertThat(capturedRequest.headers().firstValue("Content-Type"))
                .hasValue("application/json");
    }

    @Test
    @DisplayName("Should handle Gemini API error status gracefully")
    @SuppressWarnings("unchecked")
    void chat_whenApiReturnsError_returnsGracefulMessage() throws Exception {
        properties.setApiKey("test-invalid-key");

        String mockErrorResponseBody = """
                {
                  "error": {
                    "code": 400,
                    "message": "API key not valid. Please pass a valid API key.",
                    "status": "INVALID_ARGUMENT"
                  }
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(400);
        when(mockResponse.body()).thenReturn(mockErrorResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        String reply = client.chat(List.of(createUserMessage("Test")));

        assertThat(reply).contains("Unable to get response from AI Model (API key not valid. Please pass a valid API key.).");
    }

    @Test
    @DisplayName("Should handle IOException gracefully when network fails")
    @SuppressWarnings("unchecked")
    void chat_whenNetworkFails_returnsErrorMessage() throws Exception {
        properties.setApiKey("test-key");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        String reply = client.chat(List.of(createUserMessage("Test")));

        assertThat(reply).contains("Failed to communicate with AI Model: Connection refused");
    }

    @Test
    @DisplayName("Should serialize tools as functionDeclarations in request body")
    @SuppressWarnings("unchecked")
    void generateResponse_withTools_serializesFunctionDeclarationsInPayload() throws Exception {
        properties.setApiKey("test-api-key");
        properties.setModel("gemini-3.6-flash");

        Tool tool = Tool.builder("search_available_products")
                .description("Search catalog products")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string")),
                        "required", List.of("query")
                ))
                .build();

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "functionCall": {
                              "name": "search_available_products",
                              "args": {
                                "query": "charger"
                              }
                            }
                          }
                        ],
                        "role": "model"
                      }
                    }
                  ]
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        ModelResponse response = client.generateResponse(List.of(createUserMessage("Find chargers")), List.of(tool));

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.name()).isEqualTo("search_available_products");
        assertThat(call.arguments()).containsEntry("query", "charger");
    }

    @Test
    @DisplayName("Should serialize TOOL message as functionResponse in request body")
    @SuppressWarnings("unchecked")
    void generateResponse_withToolMessageInHistory_serializesFunctionResponse() throws Exception {
        properties.setApiKey("test-api-key");
        properties.setModel("gemini-3.6-flash");

        AssistantMessage userMsg = createUserMessage("Find chargers");

        AssistantMessage modelMsg = new AssistantMessage();
        modelMsg.setRole(MessageRole.ASSISTANT);
        modelMsg.setToolCallId("search_available_products");
        modelMsg.setWidgetPayload("{\"query\":\"charger\"}");

        AssistantMessage toolMsg = new AssistantMessage();
        toolMsg.setRole(MessageRole.TOOL);
        toolMsg.setToolCallId("search_available_products");
        toolMsg.setContent("Found 2 items");

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "Found 2 chargers in stock."
                          }
                        ],
                        "role": "model"
                      }
                    }
                  ]
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        ModelResponse response = client.generateResponse(List.of(userMsg, modelMsg, toolMsg), List.of());

        assertThat(response.hasToolCalls()).isFalse();
        assertThat(response.text()).isEqualTo("Found 2 chargers in stock.");
    }

    // --- WO-011 Distributed Tracing & W3C Propagation Tests ---

    @Test
    @DisplayName("Should create span with GenAI tags and inject traceparent header when tracer is present")
    @SuppressWarnings("unchecked")
    void generateResponse_withTracer_createsSpanWithTagsAndInjectsTraceparent() throws Exception {
        // Arrange
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class, org.mockito.Mockito.RETURNS_SELF);
        TraceContext traceContext = mock(TraceContext.class);

        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(traceContext.spanId()).thenReturn("00f067aa0ba902b7");

        Tool tool = Tool.builder("search_available_products")
                .description("Search catalog products")
                .build();

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "Products found"
                          }
                        ],
                        "role": "model"
                      }
                    }
                  ]
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);

        // Act
        ModelResponse response = clientWithTracer.generateResponse(
                List.of(createUserMessage("Find products")),
                List.of(tool)
        );

        // Assert
        assertThat(response.text()).isEqualTo("Products found");

        verify(tracer).nextSpan();
        verify(span).name("gemini.generate_content gemini-3.6-flash");
        verify(span).tag("gen_ai.system", "gemini");
        verify(span).tag("gen_ai.request.model", "gemini-3.6-flash");
        verify(span).tag("gen_ai.operation.name", "generateContent");
        verify(span).tag("gen_ai.client", "GeminiAiModelClient");
        verify(span).tag("peer.service", "generativelanguage.googleapis.com");
        verify(span).tag("gemini.tools.count", "1");
        verify(span).start();
        verify(span).end();

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        HttpRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.headers().firstValue("traceparent"))
                .hasValue("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    @DisplayName("Should record http.status_code and error tags on span when API returns error status")
    @SuppressWarnings("unchecked")
    void generateResponse_withTracer_recordsHttpErrorOnSpan() throws Exception {
        // Arrange
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class, org.mockito.Mockito.RETURNS_SELF);

        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(400);
        when(mockResponse.body()).thenReturn("{\"error\":{\"message\":\"Bad Request\"}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);

        // Act
        ModelResponse response = clientWithTracer.generateResponse(List.of(createUserMessage("Hello")), List.of());

        // Assert
        assertThat(response.text()).contains("Unable to get response from AI Model (Bad Request).");
        verify(span).tag("http.status_code", "400");
        verify(span).tag("error", "true");
        verify(span).end();
    }

    @Test
    @DisplayName("Should record exception, error and error.type tags on span when network fails")
    @SuppressWarnings("unchecked")
    void generateResponse_withTracer_recordsExceptionOnSpanWhenNetworkFails() throws Exception {
        // Arrange
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class, org.mockito.Mockito.RETURNS_SELF);

        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection reset"));

        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);

        // Act
        ModelResponse response = clientWithTracer.generateResponse(List.of(createUserMessage("Hello")), List.of());

        // Assert
        assertThat(response.text()).contains("Failed to communicate with AI Model: Connection reset");
        verify(span).error(any(IOException.class));
        verify(span).tag("error", "true");
        verify(span).tag("error.type", "IOException");
        verify(span).end();
    }

    @Test
    @DisplayName("Should capture token usage metadata and tool calls count on span")
    @SuppressWarnings("unchecked")
    void generateResponse_withTracer_capturesUsageMetadata() throws Exception {
        // Arrange
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class, org.mockito.Mockito.RETURNS_SELF);

        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "functionCall": {
                              "name": "search_available_products",
                              "args": { "query": "charger" }
                            }
                          }
                        ],
                        "role": "model"
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 28,
                    "candidatesTokenCount": 45,
                    "totalTokenCount": 73
                  }
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);

        // Act
        ModelResponse response = clientWithTracer.generateResponse(List.of(createUserMessage("Find chargers")), List.of());

        // Assert
        assertThat(response.hasToolCalls()).isTrue();
        verify(span).tag("gen_ai.usage.prompt_tokens", "28");
        verify(span).tag("gen_ai.usage.completion_tokens", "45");
        verify(span).tag("gen_ai.usage.total_tokens", "73");
        verify(span).tag("gemini.tool_calls.count", "1");
        verify(span).end();
    }

    @Test
    @DisplayName("Should execute normally without exceptions when tracer is null")
    @SuppressWarnings("unchecked")
    void generateResponse_withoutTracer_worksGracefully() throws Exception {
        // Arrange
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "OK without tracer"
                          }
                        ],
                        "role": "model"
                      }
                    }
                  ]
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        GeminiAiModelClient clientWithoutTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, null);

        // Act
        ModelResponse response = clientWithoutTracer.generateResponse(List.of(createUserMessage("Hello")), List.of());

        // Assert
        assertThat(response.text()).isEqualTo("OK without tracer");
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().headers().firstValue("traceparent")).isEmpty();
    }

    @Test
    @DisplayName("Should not create span when API key is unconfigured or placeholder")
    void generateResponse_withTracer_whenApiKeyUnconfigured_doesNotCreateSpan() {
        // Arrange
        properties.setApiKey("${GEMINI_API_KEY}");
        Tracer tracer = mock(Tracer.class);
        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);

        // Act
        ModelResponse response = clientWithTracer.generateResponse(List.of(createUserMessage("Hello")), List.of());

        // Assert
        assertThat(response.text()).contains("Live AI Model key is not configured");
        verify(tracer, never()).nextSpan();
    }

    @Test
    @DisplayName("Should inject tracer from ObjectProvider via primary constructor")
    @SuppressWarnings("unchecked")
    void constructor_withObjectProvider_injectsTracerProperly() {
        // Arrange
        Tracer tracer = mock(Tracer.class);
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);

        // Act
        GeminiAiModelClient clientFromProvider = new GeminiAiModelClient(properties, provider);

        // Assert
        verify(provider).getIfAvailable();
        assertThat(clientFromProvider).isNotNull();
    }

    private AssistantMessage createUserMessage(String content) {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        return msg;
    }
}
