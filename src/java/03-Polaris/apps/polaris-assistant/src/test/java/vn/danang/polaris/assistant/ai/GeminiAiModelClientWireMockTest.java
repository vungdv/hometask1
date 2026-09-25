package vn.danang.polaris.assistant.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * Verifies {@link GeminiAiModelClient} against a real HTTP server stubbed with WireMock,
 * exercising the actual request/response wire format instead of a mocked {@link java.net.http.HttpClient}.
 */
class GeminiAiModelClientWireMockTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/gemini-3.6-flash:generateContent";

    private AssistantAiProperties properties;
    private GeminiAiModelClient client;

    @BeforeEach
    void setUp() {
        properties = new AssistantAiProperties();
        properties.setApiKey("test-wiremock-api-key");
        properties.setModel("gemini-3.6-flash");
        properties.setBaseUrl(wireMock.baseUrl());
        properties.setTimeoutSeconds(5);
        client = new GeminiAiModelClient(properties);
    }

    // =========================================================================
    // 1. Happy path — real request/response over the wire
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a stubbed 200 response, when chat is invoked, then sends expected headers/body and returns text")
        void sends_expected_request_and_parses_text_reply() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("x-goog-api-key", equalTo("test-wiremock-api-key"))
                    .withRequestBody(containing("\"text\":\"Hello Polaris!\""))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "candidates": [
                                        {
                                          "content": {
                                            "parts": [
                                              { "text": "Hello! How can I help you today?" }
                                            ],
                                            "role": "model"
                                          }
                                        }
                                      ]
                                    }
                                    """)));

            String reply = client.chat(List.of(createUserMessage("Hello Polaris!")));

            assertThat(reply).isEqualTo("Hello! How can I help you today?");
            wireMock.verify(postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH))
                    .withHeader("x-goog-api-key", equalTo("test-wiremock-api-key")));
        }

        @Test
        @DisplayName("Given tool definitions, when generateResponse is invoked, then serializes functionDeclarations and parses returned tool call")
        void serializes_tools_and_parses_function_call_response() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query")
            );
            Tool tool = Tool.builder("search_available_products", schema)
                    .description("Search catalog products")
                    .build();

            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .withRequestBody(containing("\"functionDeclarations\""))
                    .withRequestBody(containing("\"search_available_products\""))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
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
                                      ]
                                    }
                                    """)));

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Find chargers")), List.of(tool));

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(response.toolCalls()).hasSize(1);
            ToolCall call = response.toolCalls().get(0);
            assertThat(call.name()).isEqualTo("search_available_products");
            assertThat(call.args()).containsEntry("query", "charger");
        }

        @Test
        @DisplayName("Given usageMetadata in the stubbed response, when generateResponse is invoked, then parsing succeeds without error")
        void parses_response_containing_usage_metadata() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "candidates": [
                                        { "content": { "parts": [ { "text": "5 items in stock." } ], "role": "model" } }
                                      ],
                                      "usageMetadata": { "promptTokenCount": 12, "candidatesTokenCount": 6, "totalTokenCount": 18 }
                                    }
                                    """)));

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Check stock")), List.of());

            assertThat(response.text()).isEqualTo("5 items in stock.");
        }
    }

    // =========================================================================
    // 2. Error handling — HTTP error statuses returned by the stubbed server
    // =========================================================================
    @Nested
    @DisplayName("2. Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Given the stub returns HTTP 400, when chat is invoked, then returns graceful error message with parsed detail")
        void returns_graceful_message_on_400_response() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "error": {
                                        "code": 400,
                                        "message": "API key not valid. Please pass a valid API key.",
                                        "status": "INVALID_ARGUMENT"
                                      }
                                    }
                                    """)));

            String reply = client.chat(List.of(createUserMessage("Test")));

            assertThat(reply).isEqualTo("Unable to get response from AI Model (API key not valid. Please pass a valid API key.).");
        }

        @Test
        @DisplayName("Given the stub returns HTTP 500 with no parseable error body, when chat is invoked, then falls back to status-only message")
        void returns_status_only_message_when_500_body_is_not_parseable() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "text/plain")
                            .withBody("internal server error")));

            String reply = client.chat(List.of(createUserMessage("Test")));

            assertThat(reply).isEqualTo("Unable to get response from AI Model (Status 500).");
        }

        @Test
        @DisplayName("Given the stub returns HTTP 429, when chat is invoked, then returns graceful error message")
        void returns_graceful_message_on_rate_limit_response() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(429)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    { "error": { "code": 429, "message": "Resource has been exhausted.", "status": "RESOURCE_EXHAUSTED" } }
                                    """)));

            String reply = client.chat(List.of(createUserMessage("Test")));

            assertThat(reply).isEqualTo("Unable to get response from AI Model (Resource has been exhausted.).");
        }
    }

    // =========================================================================
    // 3. Network edge cases — timeouts against a real socket
    // =========================================================================
    @Nested
    @DisplayName("3. Network edge cases")
    class NetworkEdgeCases {

        @Test
        @DisplayName("Given the stub delays beyond the configured timeout, when chat is invoked, then returns a communication failure message")
        void returns_failure_message_when_server_response_exceeds_timeout() {
            properties.setTimeoutSeconds(1);
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withFixedDelay((int) Duration.ofSeconds(3).toMillis())
                            .withHeader("Content-Type", "application/json")
                            .withBody("{}")));

            String reply = client.chat(List.of(createUserMessage("Test")));

            assertThat(reply).contains("Failed to communicate with AI Model:");
        }
    }

    private AssistantMessage createUserMessage(String content) {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        return msg;
    }
}
