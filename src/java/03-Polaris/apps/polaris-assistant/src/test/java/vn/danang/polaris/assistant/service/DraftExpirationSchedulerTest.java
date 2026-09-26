package vn.danang.polaris.assistant.service;

import static org.mockito.ArgumentMatchers.any;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import vn.danang.polaris.assistant.entity.AssistantOrderDraftRepository;

/** Unit tests for {@link DraftExpirationScheduler} — the sweep's own dispatch logic in isolation. */
class DraftExpirationSchedulerTest {

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given overdue drafts exist, when the sweep runs, then it calls the bulk-expire repository method exactly once")
        void expireOverdueDrafts_delegatesToRepositoryBulkUpdate() {
            AssistantOrderDraftRepository repository = mock(AssistantOrderDraftRepository.class);
            when(repository.updateStatusExpiredWhereWaitingAndPastTtl(any())).thenReturn(3);

            new DraftExpirationScheduler(repository).expireOverdueDrafts();

            verify(repository, times(1)).updateStatusExpiredWhereWaitingAndPastTtl(any());
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given no draft is overdue, when the sweep runs, then it still completes without error")
        void expireOverdueDrafts_noneOverdue_completesSilently() {
            AssistantOrderDraftRepository repository = mock(AssistantOrderDraftRepository.class);
            when(repository.updateStatusExpiredWhereWaitingAndPastTtl(any())).thenReturn(0);

            new DraftExpirationScheduler(repository).expireOverdueDrafts();

            verify(repository, times(1)).updateStatusExpiredWhereWaitingAndPastTtl(any());
        }
    }
}
