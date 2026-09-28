package vn.danang.polaris.assistant.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.service.SessionAccessDeniedException;
import vn.danang.polaris.assistant.service.SessionStore;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * {@code JpaSessionStore} against real PostgreSQL (V14 schema): sessions open on first use,
 * history survives across calls in order, and a session can only be used by its owner.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JpaSessionStoreIntegrationTest {

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String freshSessionId() {
        return "sess-" + UUID.randomUUID();
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given an unknown session id, when history is loaded, then an ACTIVE session is opened for the caller with empty history")
        void opens_session_on_first_load() {
            String sessionId = freshSessionId();

            List<AssistantMessage> history = sessionStore.loadHistory(sessionId, "user-alice");

            assertThat(history).isEmpty();
            var session = sessionRepository.findById(sessionId).orElseThrow();
            assertThat(session.getUserId()).isEqualTo("user-alice");
            assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        }

        @Test
        @DisplayName("Given two appended turns, when history is reloaded, then all messages come back in order with their fields")
        void persists_and_reloads_history_in_order() {
            String sessionId = freshSessionId();
            sessionStore.loadHistory(sessionId, "user-alice");

            AssistantMessage toolCall = AssistantMessage.of(null, MessageRole.ASSISTANT, "sig-1", "search_available_products");
            toolCall.setWidgetPayload("{\"query\":\"charger\"}");
            sessionStore.append(sessionId, "user-alice", List.of(
                    AssistantMessage.of("Find chargers"),
                    toolCall,
                    AssistantMessage.of("Found: Fast Charger 65W", MessageRole.TOOL, null, "search_available_products"),
                    AssistantMessage.of("Fast Charger 65W is $24.90.", MessageRole.ASSISTANT, "sig-2")));
            sessionStore.append(sessionId, "user-alice", List.of(AssistantMessage.of("Thanks")));

            List<AssistantMessage> history = sessionStore.loadHistory(sessionId, "user-alice");

            assertThat(history).extracting(AssistantMessage::getRole).containsExactly(
                    MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.USER);
            assertThat(history.get(1).getToolCallId()).isEqualTo("search_available_products");
            assertThat(history.get(1).getWidgetPayload()).isEqualTo("{\"query\":\"charger\"}");
            assertThat(history.get(1).getThoughtSignature()).isEqualTo("sig-1");
            assertThat(history.get(4).getContent()).isEqualTo("Thanks");
            assertThat(history).allSatisfy(m -> assertThat(m.getSessionId()).isEqualTo(sessionId));
        }
    }

    // =========================================================================
    // 2. Invalid input — cross-user access (IDOR)
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("Given a session opened by alice, when mallory loads it, then access is denied and alice keeps ownership")
        void denies_loading_another_users_session() {
            String sessionId = freshSessionId();
            sessionStore.loadHistory(sessionId, "user-alice");
            sessionStore.append(sessionId, "user-alice", List.of(AssistantMessage.of("My address is ...")));

            assertThatThrownBy(() -> sessionStore.loadHistory(sessionId, "user-mallory"))
                    .isInstanceOf(SessionAccessDeniedException.class);
            assertThat(sessionRepository.findById(sessionId).orElseThrow().getUserId()).isEqualTo("user-alice");
        }

        @Test
        @DisplayName("Given a session opened by alice, when mallory appends to it, then access is denied and nothing is written")
        void denies_appending_to_another_users_session() {
            String sessionId = freshSessionId();
            sessionStore.loadHistory(sessionId, "user-alice");

            assertThatThrownBy(() -> sessionStore.append(sessionId, "user-mallory", List.of(AssistantMessage.of("hi"))))
                    .isInstanceOf(SessionAccessDeniedException.class);
            assertThat(sessionStore.loadHistory(sessionId, "user-alice")).isEmpty();
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given legacy messages without a session (pre-V14 rows), when a session loads history, then they are not included")
        void ignores_legacy_rows_without_session() {
            jdbcTemplate.update("INSERT INTO assistant_messages (role, content) VALUES ('USER', 'legacy row')");
            String sessionId = freshSessionId();

            assertThat(sessionStore.loadHistory(sessionId, "user-alice")).isEmpty();
        }

        @Test
        @DisplayName("Given an empty batch, when appended, then nothing happens even before the session exists")
        void ignores_empty_append() {
            sessionStore.append(freshSessionId(), "user-alice", List.of());
        }
    }
}
