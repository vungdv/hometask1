package vn.danang.polaris.assistant.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatMessageRequestTest {

    @Test
    @DisplayName("Should throw IllegalArgumentException when request is null")
    void validate_withNullRequest_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ChatMessageRequest.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is null")
    void validate_withNullMessage_throwsIllegalArgumentException() {
        ChatMessageRequest request = new ChatMessageRequest(null);
        assertThatThrownBy(() -> ChatMessageRequest.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is empty")
    void validate_withEmptyMessage_throwsIllegalArgumentException() {
        ChatMessageRequest request = new ChatMessageRequest("");
        assertThatThrownBy(() -> ChatMessageRequest.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is whitespace")
    void validate_withWhitespaceMessage_throwsIllegalArgumentException() {
        ChatMessageRequest request = new ChatMessageRequest("   ");
        assertThatThrownBy(() -> ChatMessageRequest.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }

    @Test
    @DisplayName("Should succeed when request has valid message")
    void validate_withValidMessage_succeeds() {
        ChatMessageRequest request = new ChatMessageRequest("Hello Assistant");
        assertThatCode(() -> ChatMessageRequest.validate(request))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Instance validate() should throw IllegalArgumentException when message is blank")
    void instanceValidate_withBlankMessage_throwsIllegalArgumentException() {
        ChatMessageRequest request = new ChatMessageRequest("   ");
        assertThatThrownBy(request::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message content must not be blank.");
    }

    @Test
    @DisplayName("Instance validate() should succeed when message is valid")
    void instanceValidate_withValidMessage_succeeds() {
        ChatMessageRequest request = new ChatMessageRequest("Hello Assistant");
        assertThatCode(request::validate)
                .doesNotThrowAnyException();
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

    @Test
    @DisplayName("resolvedMessage should return trimmed message or empty string")
    void resolvedMessage_returnsTrimmedOrEmpty() {
        ChatMessageRequest req1 = new ChatMessageRequest("  Hello World  ");
        assertThat(req1.resolvedMessage()).isEqualTo("Hello World");

        ChatMessageRequest req2 = new ChatMessageRequest(null);
        assertThat(req2.resolvedMessage()).isEmpty();
    }
}
