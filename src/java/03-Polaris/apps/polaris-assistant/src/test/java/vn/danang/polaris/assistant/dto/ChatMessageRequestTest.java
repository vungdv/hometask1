package vn.danang.polaris.assistant.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatMessageRequestTest {
    @Test
    @DisplayName("Should throw IllegalArgumentException when message is null")
    void validate_withNullMessage_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ChatMessageRequest(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is empty")
    void validate_withEmptyMessage_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ChatMessageRequest(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is whitespace")
    void validate_withWhitespaceMessage_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ChatMessageRequest("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }

    @Test
    @DisplayName("Constructor should throw IllegalArgumentException when sessionId is provided but message is blank")
    void constructor_withBlankMessageAndSessionId_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new ChatMessageRequest("session-123", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }


    @Test
    @DisplayName("Should automatically generate sessionId when omitted or blank")
    void constructor_generatesSessionIdWhenOmitted() {
        ChatMessageRequest req1 = new ChatMessageRequest("Hello");
        assertThat(req1.sessionId()).isNotNull().isNotBlank();

        ChatMessageRequest req2 = new ChatMessageRequest("   ", "Hello");
        assertThat(req2.sessionId()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("Should preserve existing sessionId when provided")
    void constructor_preservesProvidedSessionId() {
        ChatMessageRequest request = new ChatMessageRequest("custom-session-123", "Hello");
        assertThat(request.sessionId()).isEqualTo("custom-session-123");
    }
}
