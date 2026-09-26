package vn.danang.polaris.web.exception;

/**
 * Thrown when a confirm or cancel attempt targets a draft that isn't {@code WAITING_CONFIRMATION}
 * (already confirmed, already cancelled, or already expired) — there is only one actionable state,
 * so one exception serves both the confirm and cancel endpoints (WO-021).
 */
public class DraftNotActionableException extends RuntimeException {

    private final String draftId;
    private final String currentStatus;

    public DraftNotActionableException(String draftId, String currentStatus) {
        super(String.format("Draft '%s' is not awaiting confirmation (current status: %s).", draftId, currentStatus));
        this.draftId = draftId;
        this.currentStatus = currentStatus;
    }

    public String getDraftId() {
        return draftId;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }
}
