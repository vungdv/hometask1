package vn.danang.polaris.assistant.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.assistant.TestcontainersConfiguration;

/**
 * Repository-level test proving the {@code assistant_sessions} schema (V12 migration) and the
 * {@link AssistantSession} JPA mapping agree. Runs against a real Postgres (Testcontainers), the
 * same profile used elsewhere in this module.
 */
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class AssistantSessionRepositoryTest {

    @Autowired
    private AssistantSessionRepository sessionRepository;

    private static AssistantSession newSession(String id, String userId, Long customerId) {
        AssistantSession session = new AssistantSession();
        session.setId(id);
        session.setUserId(userId);
        session.setCustomerId(customerId);
        return session;
    }

    private static String freshId() {
        return "sess-" + UUID.randomUUID();
    }

    // --- Happy path -----------------------------------------------------

    @Test
    void save_shouldPersistWithDefaultActiveStatusAndZeroVersion() {
        AssistantSession session = newSession(freshId(), "user-42", 7L);

        AssistantSession saved = sessionRepository.save(session);

        assertThat(saved.getStatus()).isEqualTo(AssistantSessionStatus.ACTIVE);
        assertThat(saved.getVersion()).isZero();
    }

    @Test
    void save_thenFindById_shouldReturnPersistedFields() {
        String id = freshId();
        AssistantSession session = newSession(id, "user-77", 3L);
        sessionRepository.saveAndFlush(session);

        var found = sessionRepository.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo("user-77");
        assertThat(found.get().getCustomerId()).isEqualTo(3L);
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    // --- Invalid input ----------------------------------------------------

    @Test
    void save_withNullUserId_shouldViolateNotNullConstraint() {
        AssistantSession session = newSession(freshId(), null, null);

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(session))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Edge cases ---------------------------------------------------------

    @Test
    void save_withNullCustomerId_shouldPersist_becauseCustomerIdIsOptional() {
        AssistantSession session = newSession(freshId(), "user-anonymous", null);

        AssistantSession saved = sessionRepository.saveAndFlush(session);

        assertThat(saved.getCustomerId()).isNull();
    }

    @Test
    void save_existingSessionWithChangedStatus_shouldIncrementOptimisticLockVersion() {
        String id = freshId();
        AssistantSession session = newSession(id, "user-99", 1L);
        AssistantSession saved = sessionRepository.saveAndFlush(session);
        long initialVersion = saved.getVersion();

        saved.setStatus(AssistantSessionStatus.WAITING_CONFIRMATION);
        saved.setUpdatedAt(Instant.now());
        AssistantSession updated = sessionRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isGreaterThan(initialVersion);
    }
}
