package vn.danang.polaris.assistant.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.entity.AssistantOrderDraftRepository;

/**
 * Proactive/eager TTL expiration, on top of {@link DraftConfirmationService#confirm}'s lazy
 * at-confirm-time check — a draft that's simply abandoned (no further confirm attempt ever made)
 * doesn't sit in {@code WAITING_CONFIRMATION} forever. Only ever touches
 * {@code assistant_order_drafts}; it never calls Catalog or Order, so it structurally cannot
 * affect any other bounded context's data.
 */
@Component
public class DraftExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DraftExpirationScheduler.class);

    private final AssistantOrderDraftRepository draftRepository;

    @Autowired
    public DraftExpirationScheduler(AssistantOrderDraftRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    @Scheduled(fixedDelayString = "${polaris.assistant.draft.expiration-sweep-interval:PT60S}")
    public void expireOverdueDrafts() {
        int expired = draftRepository.updateStatusExpiredWhereWaitingAndPastTtl(Instant.now());
        if (expired > 0) {
            log.info("Expired {} order draft(s) past TTL", expired);
        }
    }
}
