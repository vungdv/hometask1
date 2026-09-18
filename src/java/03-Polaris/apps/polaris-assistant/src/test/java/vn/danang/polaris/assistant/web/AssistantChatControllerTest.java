package vn.danang.polaris.assistant.web;

import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.service.AssistantChatService;
import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;

@WebMvcTest(AssistantChatController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AssistantChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantChatService chatService;

    // =========================================================================
    // Direct Unit Tests
    // =========================================================================

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

    @Test
    @DisplayName("Should throw IllegalArgumentException when request has null message")
    void chat_withNullMessage_throwsIllegalArgumentException() {
        AssistantChatService mockService = mock(AssistantChatService.class);
        AssistantChatController controller = new AssistantChatController(mockService);

        ChatMessageRequest request = new ChatMessageRequest(null);
        Principal principal = () -> "test-user";

        assertThatThrownBy(() -> controller.chat(request, principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");

        verifyNoInteractions(mockService);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when request has blank message")
    void chat_withBlankMessage_throwsIllegalArgumentException() {
        AssistantChatService mockService = mock(AssistantChatService.class);
        AssistantChatController controller = new AssistantChatController(mockService);

        ChatMessageRequest request = new ChatMessageRequest("   ");
        Principal principal = () -> "test-user";

        assertThatThrownBy(() -> controller.chat(request, principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");

        verifyNoInteractions(mockService);
    }

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

    // =========================================================================
    // MockMvc Web Layer Integration Tests
    // =========================================================================

    @Test
    @DisplayName("POST /api/v1/assistant/chat returns 200 OK for valid request")
    void mockMvc_chat_withValidPayload_returns200() throws Exception {
        ChatMessageResponse mockResponse = new ChatMessageResponse(
                "session-123",
                "ASSISTANT",
                "Polaris is an ecommerce platform.",
                Instant.now()
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
                .andExpect(jsonPath("$.sessionId").value("session-123"))
                .andExpect(jsonPath("$.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.reply").value("Polaris is an ecommerce platform."));
    }

    @Test
    @DisplayName("POST /api/v1/assistant/chat returns 400 Bad Request when message is blank")
    void mockMvc_chat_withBlankMessage_returns400() throws Exception {
        String json = """
                {
                    "message": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/assistant/chat returns 400 Bad Request when body is empty")
    void mockMvc_chat_withEmptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }
}
