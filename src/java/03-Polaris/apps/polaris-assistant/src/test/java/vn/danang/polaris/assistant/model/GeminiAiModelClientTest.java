package vn.danang.polaris.assistant.model;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
        //Arrange
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

        //Act
        String reply = client.chat(List.of(userMessage));

        //Assert
        assertThat(reply).isEqualTo("Hello! How can I help you today?");

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());

        HttpRequest capturedRequest = requestCaptor.getValue();
        // Verify URI does not expose API key in query string
        assertThat(capturedRequest.uri().toString())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent");
        // Verify key is sent in x-goog-api-key header
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

    private AssistantMessage createUserMessage(String content) {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        return msg;
    }
}
