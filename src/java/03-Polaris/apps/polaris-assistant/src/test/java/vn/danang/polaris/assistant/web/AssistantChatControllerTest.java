package vn.danang.polaris.assistant.web;

import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.service.AssistantChatService;
import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;

@WebMvcTest(AssistantChatController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("AssistantChatController API Contract & Validation Tests")
class AssistantChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantChatService chatService;

    // =========================================================================
    // 1. Direct Unit Tests (Pure Controller Isolation)
    // =========================================================================
    @Nested
    @DisplayName("1. Direct Unit Tests (Controller Isolation)")
    class DirectUnitTests {

        @Nested
        @DisplayName("Main / Successful Test Paths")
        class MainSuccessPaths {

            @Test
            @DisplayName("Should invoke service and return 200 OK when request is valid")
            void chat_withValidRequest_returnsOkResponse() {
                AssistantChatService mockService = mock(AssistantChatService.class);
                AssistantChatController controller = new AssistantChatController(mockService);

                ChatMessageRequest request = new ChatMessageRequest("Hello AI");
                Principal principal = () -> "test-user";
                ChatMessageResponse expectedResponse = new ChatMessageResponse(
                        request.sessionId(),
                        "ASSISTANT",
                        "Hello, how can I help you?",
                        Instant.now()
                );

                when(mockService.sendMessage(eq(request), eq("test-user"))).thenReturn(expectedResponse);

                ResponseEntity<ChatMessageResponse> response = controller.chat(request, principal);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody()).isEqualTo(expectedResponse);
                verify(mockService).sendMessage(eq(request), eq("test-user"));
            }

            @Test
            @DisplayName("Should use anonymous userId when principal is null")
            void chat_withNullPrincipal_usesAnonymousUserId() {
                AssistantChatService mockService = mock(AssistantChatService.class);
                AssistantChatController controller = new AssistantChatController(mockService);

                ChatMessageRequest request = new ChatMessageRequest("Hello AI");
                ChatMessageResponse expectedResponse = new ChatMessageResponse(
                        request.sessionId(),
                        "ASSISTANT",
                        "Hello!",
                        Instant.now()
                );

                when(mockService.sendMessage(eq(request), eq("anonymous"))).thenReturn(expectedResponse);

                ResponseEntity<ChatMessageResponse> response = controller.chat(request, null);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                verify(mockService).sendMessage(eq(request), eq("anonymous"));
            }

            @Test
            @DisplayName("Should pass authenticated principal name as userId")
            void chat_withAuthenticatedPrincipal_usesPrincipalName() {
                AssistantChatService mockService = mock(AssistantChatService.class);
                AssistantChatController controller = new AssistantChatController(mockService);

                ChatMessageRequest request = new ChatMessageRequest("sess-1", "What is my order status?");
                Principal principal = () -> "user-alice";
                ChatMessageResponse expectedResponse = new ChatMessageResponse(
                        "sess-1",
                        "ASSISTANT",
                        "Your order is confirmed.",
                        Instant.now()
                );

                when(mockService.sendMessage(eq(request), eq("user-alice"))).thenReturn(expectedResponse);

                ResponseEntity<ChatMessageResponse> response = controller.chat(request, principal);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody()).isEqualTo(expectedResponse);
                verify(mockService).sendMessage(eq(request), eq("user-alice"));
            }
        }

        @Nested
        @DisplayName("Common Invalid & Edge Cases")
        class InvalidAndEdgeCases {

            @Test
            @DisplayName("Should throw IllegalArgumentException when request is null")
            void chat_withNullRequest_throwsIllegalArgumentException() {
                AssistantChatService mockService = mock(AssistantChatService.class);
                AssistantChatController controller = new AssistantChatController(mockService);

                Principal principal = () -> "test-user";

                assertThatThrownBy(() -> controller.chat(null, principal))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Message content must not be blank.");

                verifyNoInteractions(mockService);
            }
        }
    }

    // =========================================================================
    // 2. MockMvc Web Layer API Contract Tests
    // =========================================================================
    @Nested
    @DisplayName("2. MockMvc Web Layer API Contract Tests")
    class WebLayerApiContractTests {

        @Nested
        @DisplayName("Main / Successful Test Paths (HTTP 200)")
        class SuccessfulPaths {

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 200 OK with application/json for valid payload")
            void chat_withValidPayload_returns200AndJson() throws Exception {
                Instant now = Instant.parse("2026-09-20T10:00:00Z");
                ChatMessageResponse mockResponse = new ChatMessageResponse(
                        "session-123",
                        "ASSISTANT",
                        "Polaris is an ecommerce platform.",
                        now
                );

                when(chatService.sendMessage(any(ChatMessageRequest.class), eq("anonymous")))
                        .thenReturn(mockResponse);

                String json = """
                        {
                            "sessionId": "session-123",
                            "message": "What is Polaris?"
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.sessionId").value("session-123"))
                        .andExpect(jsonPath("$.role").value("ASSISTANT"))
                        .andExpect(jsonPath("$.reply").value("Polaris is an ecommerce platform."))
                        .andExpect(jsonPath("$.createdAt").value("2026-09-20T10:00:00Z"));
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat succeeds when sessionId is omitted")
            void chat_withOmittedSessionId_returns200() throws Exception {
                ChatMessageResponse mockResponse = new ChatMessageResponse(
                        "auto-generated-session",
                        "ASSISTANT",
                        "Hello there!",
                        Instant.now()
                );

                when(chatService.sendMessage(any(ChatMessageRequest.class), eq("anonymous")))
                        .thenReturn(mockResponse);

                String json = """
                        {
                            "message": "Hello!"
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.sessionId").value("auto-generated-session"))
                        .andExpect(jsonPath("$.role").value("ASSISTANT"))
                        .andExpect(jsonPath("$.reply").value("Hello there!"));
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat forwards authenticated principal name to service")
            void chat_withAuthenticatedUser_forwardsUserId() throws Exception {
                ChatMessageResponse mockResponse = new ChatMessageResponse(
                        "session-auth",
                        "ASSISTANT",
                        "Welcome back Alice!",
                        Instant.now()
                );

                when(chatService.sendMessage(any(ChatMessageRequest.class), eq("user-alice")))
                        .thenReturn(mockResponse);

                String json = """
                        {
                            "sessionId": "session-auth",
                            "message": "Show my past orders"
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                        .jwt(jwt -> jwt.subject("user-alice")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.sessionId").value("session-auth"))
                        .andExpect(jsonPath("$.reply").value("Welcome back Alice!"));

                verify(chatService).sendMessage(any(ChatMessageRequest.class), eq("user-alice"));
            }
        }

        @Nested
        @DisplayName("Common Invalid Requests - Problem Details (HTTP 400)")
        class CommonInvalidProblemDetails {

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 400 ProblemDetail when message is empty string")
            void chat_withEmptyStringMessage_returns400ProblemDetail() throws Exception {
                String json = """
                        {
                            "sessionId": "session-123",
                            "message": ""
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                        .andExpect(jsonPath("$.title").value("Bad Request"))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.detail").value("Message content must not be blank."))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

                verifyNoInteractions(chatService);
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 400 ProblemDetail when message is whitespace-only")
            void chat_withWhitespaceOnlyMessage_returns400ProblemDetail() throws Exception {
                String json = """
                        {
                            "sessionId": "session-123",
                            "message": "     "
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                        .andExpect(jsonPath("$.title").value("Bad Request"))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.detail").value("Message content must not be blank."))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

                verifyNoInteractions(chatService);
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 400 ProblemDetail when message field is null")
            void chat_withNullMessage_returns400ProblemDetail() throws Exception {
                String json = """
                        {
                            "sessionId": "session-123",
                            "message": null
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                        .andExpect(jsonPath("$.title").value("Bad Request"))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.detail").value("Message content must not be blank."))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

                verifyNoInteractions(chatService);
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 400 ProblemDetail when message field is completely missing")
            void chat_withMissingMessageField_returns400ProblemDetail() throws Exception {
                String json = """
                        {
                            "sessionId": "session-123"
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                        .andExpect(jsonPath("$.title").value("Bad Request"))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.detail").value("Message content must not be blank."))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

                verifyNoInteractions(chatService);
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 400 ProblemDetail when request body is empty")
            void chat_withEmptyBody_returns400ProblemDetail() throws Exception {
                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(""))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                        .andExpect(jsonPath("$.title").value("Malformed Request Payload"))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.detail").value("Required request body is missing or malformed."))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

                verifyNoInteractions(chatService);
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 400 ProblemDetail when payload is malformed JSON")
            void chat_withMalformedJson_returns400ProblemDetail() throws Exception {
                String malformedJson = "{ \"message\": ";

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(malformedJson))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                        .andExpect(jsonPath("$.title").value("Malformed Request Payload"))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.detail").value("Required request body is missing or malformed."))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

                verifyNoInteractions(chatService);
            }
        }

        @Nested
        @DisplayName("Edge Cases & Protocol Exceptions (HTTP 400 / 415 / 500)")
        class EdgeCasesAndProtocolContracts {

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 400 ProblemDetail when message contains only Unicode zero-width or control characters")
            void chat_withInvisibleUnicodeCharactersOnly_returns400ProblemDetail() throws Exception {
                // \u200B is ZERO WIDTH SPACE, \u200C is ZERO WIDTH NON-JOINER
                String json = """
                        {
                            "sessionId": "session-unicode",
                            "message": "\\u200B\\u200C\\u200D"
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                        .andExpect(jsonPath("$.title").value("Bad Request"))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.detail").value("Message content must not be blank."))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

                verifyNoInteractions(chatService);
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 415 ProblemDetail when Content-Type is unsupported")
            void chat_withUnsupportedMediaType_returns415ProblemDetail() throws Exception {
                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("Hello AI"))
                        .andExpect(status().isUnsupportedMediaType())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/unsupported-media-type"))
                        .andExpect(jsonPath("$.title").value("Unsupported Media Type"))
                        .andExpect(jsonPath("$.status").value(415))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

                verifyNoInteractions(chatService);
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 409 ProblemDetail when domain state conflict occurs")
            void chat_whenDomainConflictOccurs_returns409ProblemDetail() throws Exception {
                when(chatService.sendMessage(any(ChatMessageRequest.class), any()))
                        .thenThrow(new IllegalStateException("AI model provider connection refused"));

                String json = """
                        {
                            "sessionId": "session-error",
                            "message": "Find chargers in catalog"
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isConflict())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/conflict"))
                        .andExpect(jsonPath("$.title").value("Order State Conflict"))
                        .andExpect(jsonPath("$.status").value(409));
            }

            @Test
            @DisplayName("POST /api/v1/assistant/chat returns 500 ProblemDetail on unexpected internal exception")
            void chat_whenUnexpectedException_returns500ProblemDetail() throws Exception {
                when(chatService.sendMessage(any(ChatMessageRequest.class), any()))
                        .thenThrow(new RuntimeException("Unexpected runtime error"));

                String json = """
                        {
                            "sessionId": "session-err",
                            "message": "Tell me a joke"
                        }
                        """;

                mockMvc.perform(post("/api/v1/assistant/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isInternalServerError())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("https://polaris.local/errors/internal-error"))
                        .andExpect(jsonPath("$.title").value("Internal Server Error"))
                        .andExpect(jsonPath("$.status").value(500))
                        .andExpect(jsonPath("$.detail").value("Internal server error during processing."))
                        .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));
            }
        }
    }
}
