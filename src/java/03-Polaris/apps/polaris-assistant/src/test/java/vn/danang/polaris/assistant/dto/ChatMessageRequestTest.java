package vn.danang.polaris.assistant.dto;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Feature: ChatMessageRequest Contract & Invariant Validation")
class ChatMessageRequestTest {

    // =========================================================================
    // 1. Happy path — main successful flows
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a custom sessionId, when request is constructed, then preserves the custom sessionId")
        void preserves_custom_session_id_when_provided() {
            // Given
            String customSessionId = "custom-session-123";

            // When
            ChatMessageRequest request = new ChatMessageRequest(customSessionId, "Hello Polaris");

            // Then
            assertThat(request.sessionId()).isEqualTo(customSessionId);
        }

        @Test
        @DisplayName("Given a valid message, when request is constructed, then retains the message content")
        void retains_message_content() {
            // Given
            String messageContent = "Hello Polaris";

            // When
            ChatMessageRequest request = new ChatMessageRequest("custom-session-123", messageContent);

            // Then
            assertThat(request.message()).isEqualTo(messageContent);
        }

        @Test
        @DisplayName("Given only a message without sessionId, when single-argument constructor is invoked, then generates a valid UUID sessionId")
        void generates_valid_uuid_session_id_when_omitted() {
            // Given
            String message = "Hello Polaris";

            // When
            ChatMessageRequest request = new ChatMessageRequest(message);

            // Then
            assertThatCode(() -> UUID.fromString(request.sessionId())).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // 2. Invalid input — common validation and error cases
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @ParameterizedTest(name = "Given invalid message \"{0}\", when constructed, then throws IllegalArgumentException")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n", " \t \r\n "})
        @DisplayName("Given null, empty, or whitespace message, when constructed, then throws IllegalArgumentException")
        void rejects_null_empty_or_whitespace_message(String invalidMessage) {
            // When / Then
            assertThatThrownBy(() -> new ChatMessageRequest(invalidMessage))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Message content must not be blank.");
        }

        @ParameterizedTest(name = "Given sessionId and blank message \"{0}\", when constructed, then throws IllegalArgumentException")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("Given a valid sessionId and blank message, when constructed, then throws IllegalArgumentException")
        void rejects_blank_message_even_when_session_id_is_provided(String blankMessage) {
            // Given
            String sessionId = "session-123";

            // When / Then
            assertThatThrownBy(() -> new ChatMessageRequest(sessionId, blankMessage))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Message content must not be blank.");
        }
    }

    // =========================================================================
    // 3. Edge cases — boundaries, empty/null, Unicode control characters
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @ParameterizedTest(name = "Given blank sessionId \"{0}\", when constructed, then generates a valid UUID sessionId")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n"})
        @DisplayName("Given a blank or null sessionId, when constructed, then generates a valid UUID sessionId")
        void generates_uuid_session_id_when_provided_session_id_is_blank(String blankSessionId) {
            // When
            ChatMessageRequest request = new ChatMessageRequest(blankSessionId, "Hello Polaris");

            // Then
            assertThatCode(() -> UUID.fromString(request.sessionId())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Given a message with invisible Unicode and surrounding whitespace, when constructed, then strips invisible characters and trims whitespace")
        void strips_invisible_unicode_and_trims_surrounding_whitespace() {
            // Given
            // \u200B = zero-width space (Cf), \u0000 = null character (Cc), \u200E = left-to-right mark (Cf)
            String rawMessage = "  \u200BHello \u0000Polaris!\u200E  ";

            // When
            ChatMessageRequest request = new ChatMessageRequest(rawMessage);

            // Then
            assertThat(request.message()).isEqualTo("Hello Polaris!");
        }

        @ParameterizedTest(name = "Given invisible/control character payload \"{0}\", when constructed, then throws IllegalArgumentException")
        @ValueSource(strings = {
                "\u200B",                 // Zero-width space (Cf)
                "\u200B\u200C\u200D",     // Zero-width space, non-joiner, joiner (Cf)
                "\u0000\u0007",           // Control chars (NUL, BEL) (Cc)
                "  \u200B  \u0000  "      // Mixed whitespace and invisible control chars
        })
        @DisplayName("Given a message consisting solely of invisible format or control characters, when constructed, then throws IllegalArgumentException")
        void rejects_message_consisting_solely_of_invisible_or_control_characters(String invisibleMessage) {
            // When / Then
            assertThatThrownBy(() -> new ChatMessageRequest(invisibleMessage))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Message content must not be blank.");
        }
    }
}
