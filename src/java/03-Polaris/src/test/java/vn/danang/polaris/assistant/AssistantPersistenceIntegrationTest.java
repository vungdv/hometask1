package vn.danang.polaris.assistant;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.assistant.dto.DraftItemDto;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.AssistantOrderDraftRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.assistant.service.AssistantSessionService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@SpringBootTest
@Transactional
public class AssistantPersistenceIntegrationTest {

    @Autowired
    private AssistantSessionService sessionService;

    @Autowired
    private AssistantDraftService draftService;

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @Autowired
    private AssistantMessageRepository messageRepository;

    @Autowired
    private AssistantOrderDraftRepository draftRepository;

    @Test
    void createSession_shouldPersistSessionWithActiveStatusAndDefaults() {
        AssistantSession session = sessionService.createSession("user-123", 1L);

        assertThat(session).isNotNull();
        assertThat(session.getId()).isNotBlank();
        assertThat(session.getUserId()).isEqualTo("user-123");
        assertThat(session.getCustomerId()).isEqualTo(1L);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session.getVersion()).isEqualTo(0L);
        assertThat(session.getCreatedAt()).isNotNull();
        assertThat(session.getUpdatedAt()).isNotNull();

        // Verify retrieval from DB
        AssistantSession retrieved = sessionService.getSession(session.getId());
        assertThat(retrieved.getId()).isEqualTo(session.getId());
        assertThat(retrieved.getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void getSession_whenNotFound_shouldThrowResourceNotFoundException() {
        assertThatThrownBy(() -> sessionService.getSession("non-existent-session-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Assistant session not found with id: non-existent-session-id");
    }

    @Test
    void closeSession_shouldTransitionStatusToClosed() {
        AssistantSession session = sessionService.createSession("user-456", 2L);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.ACTIVE);

        AssistantSession closed = sessionService.closeSession(session.getId());
        assertThat(closed.getStatus()).isEqualTo(SessionStatus.CLOSED);

        AssistantSession reloaded = sessionService.getSession(session.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SessionStatus.CLOSED);
    }

    @Test
    void stageDraft_shouldSaveWithWaitingConfirmationAnd15MinTtl() {
        AssistantSession session = sessionService.createSession("shopper-alice", 1L);

        List<DraftItemDto> items = List.of(
                new DraftItemDto("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.95"), new BigDecimal("49.95")),
                new DraftItemDto("NG-CHARGER-01", "Nova GaN Fast Charger 65W", 2, new BigDecimal("24.90"), new BigDecimal("49.80"))
        );

        Instant beforeStage = Instant.now();
        AssistantOrderDraft draft = draftService.stageDraft(session.getId(), 1L, items);
        Instant afterStage = Instant.now();

        assertThat(draft).isNotNull();
        assertThat(draft.getId()).isNotBlank();
        assertThat(draft.getSession().getId()).isEqualTo(session.getId());
        assertThat(draft.getCustomerId()).isEqualTo(1L);
        assertThat(draft.getStatus()).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
        assertThat(draft.getTotalAmount()).isEqualByComparingTo(new BigDecimal("99.75"));
        assertThat(draft.getItems()).hasSize(2);
        assertThat(draft.getItems().get(0).sku()).isEqualTo("NG-EARBUD-01");
        assertThat(draft.getItems().get(1).quantity()).isEqualTo(2);

        // Verification of TTL (15 minutes in the future)
        assertThat(draft.getExpiresAt()).isAfterOrEqualTo(beforeStage.plus(Duration.ofMinutes(15)));
        assertThat(draft.getExpiresAt()).isBeforeOrEqualTo(afterStage.plus(Duration.ofMinutes(15)));

        // Verify active draft retrieval
        Optional<AssistantOrderDraft> activeDraft = draftService.getActiveDraft(session.getId());
        assertThat(activeDraft).isPresent();
        assertThat(activeDraft.get().getId()).isEqualTo(draft.getId());
    }

    @Test
    void stageDraft_inClosedSession_shouldThrowIllegalStateException() {
        AssistantSession session = sessionService.createSession("shopper-bob", 2L);
        sessionService.closeSession(session.getId());

        List<DraftItemDto> items = List.of(
                new DraftItemDto("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.95"), null)
        );

        assertThatThrownBy(() -> draftService.stageDraft(session.getId(), 2L, items))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot stage draft in a CLOSED session");
    }

    @Test
    void stageDraft_multipleTimes_shouldSupersedePreviousDraft() {
        AssistantSession session = sessionService.createSession("staff-user", 1L);

        List<DraftItemDto> firstItems = List.of(
                new DraftItemDto("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.95"), null)
        );
        AssistantOrderDraft draft1 = draftService.stageDraft(session.getId(), 1L, firstItems);

        List<DraftItemDto> secondItems = List.of(
                new DraftItemDto("NG-WATCH-01", "Nova Smartwatch Pro", 1, new BigDecimal("199.00"), null)
        );
        AssistantOrderDraft draft2 = draftService.stageDraft(session.getId(), 1L, secondItems);

        // Verify draft1 was superseded / cancelled
        AssistantOrderDraft reloaded1 = draftRepository.findById(draft1.getId()).orElseThrow();
        assertThat(reloaded1.getStatus()).isEqualTo(DraftStatus.CANCELLED);

        // Verify draft2 is the active draft
        Optional<AssistantOrderDraft> activeDraft = draftService.getActiveDraft(session.getId());
        assertThat(activeDraft).isPresent();
        assertThat(activeDraft.get().getId()).isEqualTo(draft2.getId());
        assertThat(activeDraft.get().getStatus()).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
    }

    @Test
    void getDraft_whenTtlPassed_shouldTransitionToExpired() {
        AssistantSession session = sessionService.createSession("user-exp", 1L);

        List<DraftItemDto> items = List.of(
                new DraftItemDto("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.95"), null)
        );

        AssistantOrderDraft draft = draftService.stageDraft(session.getId(), 1L, items);

        // Simulate TTL expiration in the past
        draft.setExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));
        draftRepository.save(draft);

        // Query draft — status should transition to EXPIRED
        AssistantOrderDraft retrieved = draftService.getDraft(draft.getId());
        assertThat(retrieved.getStatus()).isEqualTo(DraftStatus.EXPIRED);

        // Query active draft for session — should return empty because it expired
        Optional<AssistantOrderDraft> activeDraft = draftService.getActiveDraft(session.getId());
        assertThat(activeDraft).isEmpty();
    }

    @Test
    void expireDrafts_shouldBatchUpdateExpiredDrafts() {
        AssistantSession session1 = sessionService.createSession("user-batch-1", 1L);
        AssistantSession session2 = sessionService.createSession("user-batch-2", 2L);

        List<DraftItemDto> items = List.of(
                new DraftItemDto("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.95"), null)
        );

        AssistantOrderDraft draft1 = draftService.stageDraft(session1.getId(), 1L, items);
        AssistantOrderDraft draft2 = draftService.stageDraft(session2.getId(), 2L, items);

        // Expire draft1 and draft2
        draft1.setExpiresAt(Instant.now().minus(Duration.ofMinutes(10)));
        draft2.setExpiresAt(Instant.now().minus(Duration.ofMinutes(2)));
        draftRepository.save(draft1);
        draftRepository.save(draft2);

        int expiredCount = draftService.expireDrafts();
        assertThat(expiredCount).isGreaterThanOrEqualTo(2);

        AssistantOrderDraft reloaded1 = draftRepository.findById(draft1.getId()).orElseThrow();
        AssistantOrderDraft reloaded2 = draftRepository.findById(draft2.getId()).orElseThrow();
        assertThat(reloaded1.getStatus()).isEqualTo(DraftStatus.EXPIRED);
        assertThat(reloaded2.getStatus()).isEqualTo(DraftStatus.EXPIRED);
    }

    @Test
    void messages_shouldPersistAndQueryChronologically() {
        AssistantSession session = sessionService.createSession("user-msg", 1L);

        AssistantMessage msg1 = new AssistantMessage();
        msg1.setSession(session);
        msg1.setRole(MessageRole.USER);
        msg1.setContent("Find fast chargers under $30 in stock");
        msg1.setCreatedAt(Instant.now().minus(Duration.ofSeconds(10)));
        messageRepository.save(msg1);

        AssistantMessage msg2 = new AssistantMessage();
        msg2.setSession(session);
        msg2.setRole(MessageRole.ASSISTANT);
        msg2.setContent("Found 1 charger: NG-CHARGER-01 at $24.90");
        msg2.setWidgetType("PRODUCT_CARD");
        msg2.setWidgetPayload("{\"sku\": \"NG-CHARGER-01\", \"price\": 24.90}");
        msg2.setCreatedAt(Instant.now().minus(Duration.ofSeconds(5)));
        messageRepository.save(msg2);

        List<AssistantMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(messages.get(0).getContent()).contains("Find fast chargers");
        assertThat(messages.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(messages.get(1).getWidgetType()).isEqualTo("PRODUCT_CARD");
        assertThat(messages.get(1).getWidgetPayload()).contains("NG-CHARGER-01");
    }
}
