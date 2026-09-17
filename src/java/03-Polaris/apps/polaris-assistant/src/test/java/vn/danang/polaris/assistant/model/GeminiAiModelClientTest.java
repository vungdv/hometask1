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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        verify(span).tag("gen_ai.operation.name", "chat");
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
        verify(span).tag("gen_ai.usage.input_tokens", "28");
        verify(span).tag("gen_ai.usage.output_tokens", "45");
        verify(span, never()).tag(eq("gen_ai.usage.prompt_tokens"), anyString());
        verify(span, never()).tag(eq("gen_ai.usage.completion_tokens"), anyString());
        verify(span).tag("gen_ai.usage.total_tokens", "73");
        verify(span).tag("gemini.tool_calls.count", "1");
        verify(span).tag("gen_ai.response.finish_reason", "tool_calls");
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

    // --- Thought Signature Tests (Gemini 3/2.5 function calling validation) ---

    @Test
    @DisplayName("Should parse thoughtSignature from response candidate part and populate ToolCall")
    @SuppressWarnings("unchecked")
    void generateResponse_withThoughtSignature_parsesAndPopulatesToolCall() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "functionCall": {
                              "name": "default_api:search_available_products",
                              "args": { "query": "charger" }
                            },
                            "thoughtSignature": "sig_base64_encoded_12345"
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

        ModelResponse response = client.generateResponse(List.of(createUserMessage("Find chargers")), List.of());

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.name()).isEqualTo("default_api:search_available_products");
        assertThat(call.thoughtSignature()).isEqualTo("sig_base64_encoded_12345");
    }

    @Test
    @DisplayName("Should parse snake_case thought_signature from response candidate part as fallback")
    @SuppressWarnings("unchecked")
    void generateResponse_withSnakeCaseThoughtSignature_parsesAndPopulatesToolCall() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "functionCall": {
                              "name": "default_api:search_available_products",
                              "args": { "query": "phone" },
                              "thought_signature": "nested_snake_case_sig_999"
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

        ModelResponse response = client.generateResponse(List.of(createUserMessage("Find phone")), List.of());

        assertThat(response.hasToolCalls()).isTrue();
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.thoughtSignature()).isEqualTo("nested_snake_case_sig_999");
    }

    @Test
    @DisplayName("Should ignore internal thought text in final response while preserving thoughtSignature")
    @SuppressWarnings("unchecked")
    void generateResponse_withThoughtPart_ignoresThoughtTextInReply() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "thought": true,
                            "text": "Internal chain-of-thought: user wants a phone, looking at catalog...",
                            "thoughtSignature": "internal_sig_111"
                          },
                          {
                            "text": "Here are the top phones available in store.",
                            "thoughtSignature": "final_text_sig_222"
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

        ModelResponse response = client.generateResponse(List.of(createUserMessage("Find phones")), List.of());

        assertThat(response.text()).isEqualTo("Here are the top phones available in store.");
        assertThat(response.thoughtSignature()).isEqualTo("final_text_sig_222");
    }

    @Test
    @DisplayName("Should include thoughtSignature in HTTP request body when message has saved signature")
    @SuppressWarnings("unchecked")
    void generateResponse_withSavedThoughtSignature_serializesSignatureInRequest() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        AssistantMessage userMsg = createUserMessage("Find chargers");

        AssistantMessage modelMsg = new AssistantMessage();
        modelMsg.setRole(MessageRole.ASSISTANT);
        modelMsg.setToolCallId("default_api:search_available_products");
        modelMsg.setWidgetPayload("{\"query\":\"charger\"}");
        modelMsg.setThoughtSignature("sig_previous_turn_token_456");

        AssistantMessage toolMsg = new AssistantMessage();
        toolMsg.setRole(MessageRole.TOOL);
        toolMsg.setToolCallId("default_api:search_available_products");
        toolMsg.setContent("Found 2 items");

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Found 2 chargers\"}],\"role\":\"model\"}}]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        client.generateResponse(List.of(userMsg, modelMsg, toolMsg), List.of());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());

        String requestPayload = extractRequestBody(requestCaptor.getValue());

        assertThat(requestPayload).contains("\"thoughtSignature\":\"sig_previous_turn_token_456\"");
        assertThat(requestPayload).contains("\"name\":\"default_api:search_available_products\"");
    }

    @Test
    @DisplayName("Should inject bypass signature 'skip_thought_signature_validator' when function call lacks signature")
    @SuppressWarnings("unchecked")
    void generateResponse_withoutThoughtSignature_injectsBypassSignatureToPreventGeminiValidationError() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        AssistantMessage userMsg = createUserMessage("Find chargers");

        AssistantMessage modelMsg = new AssistantMessage();
        modelMsg.setRole(MessageRole.ASSISTANT);
        modelMsg.setToolCallId("default_api:search_available_products");
        modelMsg.setWidgetPayload("{\"query\":\"charger\"}");
        // thoughtSignature is null

        AssistantMessage toolMsg = new AssistantMessage();
        toolMsg.setRole(MessageRole.TOOL);
        toolMsg.setToolCallId("default_api:search_available_products");
        toolMsg.setContent("Found 2 items");

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Found 2 chargers\"}],\"role\":\"model\"}}]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        client.generateResponse(List.of(userMsg, modelMsg, toolMsg), List.of());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());

        String requestPayload = extractRequestBody(requestCaptor.getValue());

        assertThat(requestPayload).contains("\"thoughtSignature\":\"skip_thought_signature_validator\"");
    }

    @Test
    @DisplayName("Should merge parallel function calls and only attach thoughtSignature to first part")
    @SuppressWarnings("unchecked")
    void generateResponse_withParallelToolCalls_mergesIntoSingleContentAndOnlySignsFirstPart() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");

        AssistantMessage userMsg = createUserMessage("Find products and deals");

        AssistantMessage modelMsg1 = new AssistantMessage();
        modelMsg1.setRole(MessageRole.ASSISTANT);
        modelMsg1.setToolCallId("search_products");
        modelMsg1.setWidgetPayload("{\"query\":\"charger\"}");
        modelMsg1.setThoughtSignature("sig_parallel_first");

        AssistantMessage modelMsg2 = new AssistantMessage();
        modelMsg2.setRole(MessageRole.ASSISTANT);
        modelMsg2.setToolCallId("search_promotions");
        modelMsg2.setWidgetPayload("{\"type\":\"discount\"}");
        modelMsg2.setThoughtSignature(null); // parallel subsequent call has no signature

        AssistantMessage toolMsg1 = new AssistantMessage();
        toolMsg1.setRole(MessageRole.TOOL);
        toolMsg1.setToolCallId("search_products");
        toolMsg1.setContent("Found products");

        AssistantMessage toolMsg2 = new AssistantMessage();
        toolMsg2.setRole(MessageRole.TOOL);
        toolMsg2.setToolCallId("search_promotions");
        toolMsg2.setContent("Found deals");

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Found deals\"}],\"role\":\"model\"}}]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        client.generateResponse(List.of(userMsg, modelMsg1, modelMsg2, toolMsg1, toolMsg2), List.of());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());

        String requestPayload = extractRequestBody(requestCaptor.getValue());

        // Must contain sig on the first part
        assertThat(requestPayload).contains("\"thoughtSignature\":\"sig_parallel_first\"");
        // Must NOT contain skip_thought_signature_validator for the second parallel part
        assertThat(requestPayload).doesNotContain("skip_thought_signature_validator");
    }

    // --- WO-016 OpenTelemetry GenAI Semantic Conventions Tests ---

    @Test
    @DisplayName("Should attach standard OTel GenAI semantic convention tags for chat and token usage")
    @SuppressWarnings("unchecked")
    void generateResponse_withOtelGenAiSemanticConventions() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-2.5-flash");

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
                            "text": "The inventory has 5 items available."
                          }
                        ],
                        "role": "model"
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 150,
                    "candidatesTokenCount": 50,
                    "totalTokenCount": 200
                  }
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);

        ModelResponse response = clientWithTracer.generateResponse(
                List.of(createUserMessage("Check stock")),
                List.of()
        );

        assertThat(response.text()).isEqualTo("The inventory has 5 items available.");
        verify(span).tag("gen_ai.operation.name", "chat");
        verify(span).tag("gen_ai.usage.input_tokens", "150");
        verify(span).tag("gen_ai.usage.output_tokens", "50");
        verify(span).tag("gen_ai.usage.total_tokens", "200");
        verify(span).tag("gen_ai.response.finish_reason", "stop");
    }

    @Test
    @DisplayName("Should set finish_reason to tool_calls when functionCall is returned")
    @SuppressWarnings("unchecked")
    void generateResponse_withToolCalls_setsFinishReasonToolCalls() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-2.5-flash");

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
                              "name": "search_products",
                              "args": { "query": "keyboard" }
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

        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);

        ModelResponse response = clientWithTracer.generateResponse(
                List.of(createUserMessage("Find keyboard")),
                List.of()
        );

        assertThat(response.hasToolCalls()).isTrue();
        verify(span).tag("gen_ai.response.finish_reason", "tool_calls");
    }

    @Test
    @DisplayName("Should attach reasoning context tags when ModelRequestContext is provided")
    @SuppressWarnings("unchecked")
    void generateResponse_withRequestContext_attachesContextTags() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-2.5-flash");

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
                            "text": "Found 3 matching products"
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
        ModelRequestContext context = new ModelRequestContext(2, "catalog.product.search", 0.95, 3);

        ModelResponse response = clientWithTracer.generateResponse(
                List.of(createUserMessage("Search products")),
                List.of(),
                context
        );

        assertThat(response.text()).isEqualTo("Found 3 matching products");
        verify(span).tag("agent.iteration", "2");
        verify(span).tag("agent.intent_id", "catalog.product.search");
        verify(span).tag("agent.intent_confidence", "0.95");
        verify(span).tag("agent.tools_offered_count", "3");
    }

    @Test
    @DisplayName("Should strictly guarantee that no raw prompt, completion text, or thought signatures leak into span tags")
    @SuppressWarnings("unchecked")
    void generateResponse_strictExclusion_noRawPromptOrThoughtSignatureInSpanTags() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-2.5-flash");

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class, org.mockito.Mockito.RETURNS_SELF);

        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        String sensitiveUserPrompt = "My password is superSecret123 and credit card is 4111222233334444";
        String sensitiveCompletionText = "Sensitive completion content that must never be in span tags";
        String sensitiveThoughtSignature = "sig_super_secret_opaque_thought_signature_blob";

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "Sensitive completion content that must never be in span tags",
                            "thoughtSignature": "sig_super_secret_opaque_thought_signature_blob"
                          }
                        ],
                        "role": "model"
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 50,
                    "candidatesTokenCount": 20,
                    "totalTokenCount": 70
                  }
                }
                """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(mockResponseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);
        ModelRequestContext context = new ModelRequestContext(1, "catalog.search", 0.99, 1);

        ModelResponse response = clientWithTracer.generateResponse(
                List.of(createUserMessage(sensitiveUserPrompt)),
                List.of(),
                context
        );

        assertThat(response.text()).isEqualTo(sensitiveCompletionText);
        assertThat(response.thoughtSignature()).isEqualTo(sensitiveThoughtSignature);

        ArgumentCaptor<String> tagKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tagValueCaptor = ArgumentCaptor.forClass(String.class);
        verify(span, org.mockito.Mockito.atLeastOnce()).tag(tagKeyCaptor.capture(), tagValueCaptor.capture());

        List<String> capturedKeys = tagKeyCaptor.getAllValues();
        List<String> capturedValues = tagValueCaptor.getAllValues();

        // 1. Prohibited tag keys check
        assertThat(capturedKeys)
                .noneMatch(key -> key.equalsIgnoreCase("prompt")
                        || key.equalsIgnoreCase("raw_prompt")
                        || key.equalsIgnoreCase("text")
                        || key.equalsIgnoreCase("completion")
                        || key.equalsIgnoreCase("thought")
                        || key.equalsIgnoreCase("thoughtSignature")
                        || key.equalsIgnoreCase("thought_signature")
                        || key.contains("message"));

        // 2. Prohibited tag values check (leakage prevention)
        assertThat(capturedValues)
                .noneMatch(val -> val.contains(sensitiveUserPrompt)
                        || val.contains(sensitiveCompletionText)
                        || val.contains(sensitiveThoughtSignature));
    }

    @Test
    @DisplayName("Should operate gracefully without exceptions when tracer is null and context is provided")
    @SuppressWarnings("unchecked")
    void generateResponse_withNullTracer_operatesGracefully() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-2.5-flash");

        String mockResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "Graceful without tracer"
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
        ModelRequestContext context = new ModelRequestContext(1, "catalog.search", 1.0, 2);

        ModelResponse response = clientWithoutTracer.generateResponse(
                List.of(createUserMessage("Hello")),
                List.of(),
                context
        );

        assertThat(response.text()).isEqualTo("Graceful without tracer");
    }

    @Test
    @DisplayName("Should handle null ModelRequestContext gracefully without throwing NPE")
    @SuppressWarnings("unchecked")
    void generateResponse_withNullContext_operatesGracefully() throws Exception {
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-2.5-flash");

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class, org.mockito.Mockito.RETURNS_SELF);

        when(tracer.nextSpan()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OK\"}],\"role\":\"model\"}}]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        GeminiAiModelClient clientWithTracer = new GeminiAiModelClient(properties, httpClient, objectMapper, tracer);

        ModelResponse response = clientWithTracer.generateResponse(
                List.of(createUserMessage("Hello")),
                List.of(),
                null
        );

        assertThat(response.text()).isEqualTo("OK");
        verify(span).tag("gen_ai.operation.name", "chat");
        verify(span, never()).tag(org.mockito.ArgumentMatchers.startsWith("agent."), any());
    }

    private String extractRequestBody(HttpRequest request) {
        if (request.bodyPublisher().isEmpty()) {
            return "";
        }
        var flowSubscriber = HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
        request.bodyPublisher().get().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                flowSubscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                flowSubscriber.onNext(List.of(item));
            }

            @Override
            public void onError(Throwable throwable) {
                flowSubscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                flowSubscriber.onComplete();
            }
        });
        return flowSubscriber.getBody().toCompletableFuture().join();
    }

    private AssistantMessage createUserMessage(String content) {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        return msg;
    }
}
