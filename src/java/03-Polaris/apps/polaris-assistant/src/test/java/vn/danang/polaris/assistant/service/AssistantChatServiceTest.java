package vn.danang.polaris.assistant.service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.model.AssistantModelClient;

class AssistantChatServiceTest {

    private AssistantModelClient modelClient;
    private AssistantChatService chatService;

    @BeforeEach
    void setUp() {
        modelClient = mock(AssistantModelClient.class);
        chatService = new AssistantChatService(modelClient);
    }

    @Test
    @DisplayName("Should forward user message to AssistantModelClient and return response")
    @SuppressWarnings("unchecked")
    void sendMessage_withValidMessage_forwardsToModelClientAndReturnsResponse() {
        ChatMessageRequest request = new ChatMessageRequest("Tell me about Polaris");
        when(modelClient.chat(anyList())).thenReturn("Polaris is an enterprise ecommerce platform.");

        ChatMessageResponse response = chatService.sendMessage(request, "user-123");

        assertThat(response).isNotNull();
        assertThat(response.role()).isEqualTo("ASSISTANT");
        assertThat(response.reply()).isEqualTo("Polaris is an enterprise ecommerce platform.");
        assertThat(response.createdAt()).isNotNull();

        ArgumentCaptor<List<AssistantMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelClient).chat(captor.capture());

        List<AssistantMessage> sentMessages = captor.getValue();
        assertThat(sentMessages).hasSize(1);
        assertThat(sentMessages.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(sentMessages.get(0).getContent()).isEqualTo("Tell me about Polaris");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is blank")
    void sendMessage_withBlankMessage_throwsIllegalArgumentException() {
        ChatMessageRequest request = new ChatMessageRequest("   ");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> chatService.sendMessage(request, "user-123"));

        assertThat(exception).hasMessage("Message content must not be blank.");
        verify(modelClient, never()).chat(anyList());
    }
}
