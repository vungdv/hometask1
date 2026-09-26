package vn.danang.polaris.assistant.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.dto.DraftItemSnapshot;
import vn.danang.polaris.assistant.dto.OrderItemRequest;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftRepository;
import vn.danang.polaris.assistant.entity.AssistantOrderDraftStatus;
import vn.danang.polaris.assistant.entity.AssistantSessionRepository;
import vn.danang.polaris.assistant.entity.AssistantSessionStatus;
import vn.danang.polaris.assistant.tools.CatalogRestClient;
import vn.danang.polaris.assistant.tools.CatalogRestClient.CatalogProductView;
import vn.danang.polaris.assistant.tools.OrderPlacementRejectedException;
import vn.danang.polaris.assistant.tools.OrderRestClient;
import vn.danang.polaris.assistant.tools.OrderRestClient.OrderPlacedView;
import vn.danang.polaris.web.exception.DraftExpiredException;
import vn.danang.polaris.web.exception.DraftNotActionableException;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

/**
 * Confirm/cancel business logic (WO-021). Confirming calls Order's published
 * {@code POST /api/v1/orders} via {@link OrderRestClient} — never {@code OrderService} directly,
 * never an Order-context JPA repository. The scheduled TTL sweep lives separately in
 * {@link DraftExpirationScheduler}; this class only handles the lazy, at-confirm-time path.
 */
@Service
public class DraftConfirmationService {

    /** Outcome of a confirm attempt that reached the state/TTL guards without throwing. */
    public sealed interface ConfirmOutcome {
        record Confirmed(String orderNumber, String rawOrderResponseBody) implements ConfirmOutcome {
        }

        /** Live re-verification (Task 5 step 4) failed — the draft is left exactly as it was. */
        record StockRejected(String sku, int requested, int available) implements ConfirmOutcome {
        }

        /** Order rejected the placement itself (e.g. stock exhausted in the race window) — proxied as-is. */
        record DownstreamRejected(int statusCode, String rawProblemBody) implements ConfirmOutcome {
        }
    }

    private final AssistantOrderDraftRepository draftRepository;
    private final AssistantSessionRepository sessionRepository;
    private final CatalogRestClient catalogRestClient;
    private final OrderRestClient orderRestClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public DraftConfirmationService(
            AssistantOrderDraftRepository draftRepository,
            AssistantSessionRepository sessionRepository,
            CatalogRestClient catalogRestClient,
            OrderRestClient orderRestClient,
            ObjectMapper objectMapper) {
        this.draftRepository = draftRepository;
        this.sessionRepository = sessionRepository;
        this.catalogRestClient = catalogRestClient;
        this.orderRestClient = orderRestClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConfirmOutcome confirm(String sessionId, String draftId, String idempotencyKey) {
        AssistantOrderDraft draft = requireDraft(sessionId, draftId);

        if (draft.getStatus() != AssistantOrderDraftStatus.WAITING_CONFIRMATION) {
            throw new DraftNotActionableException(draftId, draft.getStatus().name());
        }

        if (Instant.now().isAfter(draft.getExpiresAt())) {
            draft.setStatus(AssistantOrderDraftStatus.EXPIRED);
            draft.setUpdatedAt(Instant.now());
            draftRepository.save(draft);
            throw new DraftExpiredException(draftId,
                    String.format("Draft '%s' expired at %s.", draftId, draft.getExpiresAt()));
        }

        List<DraftItemSnapshot> items = deserializeItems(draft.getItemsJson());

        for (DraftItemSnapshot item : items) {
            Optional<CatalogProductView> product = catalogRestClient.getBySku(item.sku());
            int available = product.map(CatalogProductView::stockQuantity).filter(q -> q != null).orElse(0);
            if (item.quantity() > available) {
                // Live stock dropped below the snapshotted quantity since staging — do not
                // confirm, do not mutate the draft, let the shopper re-stage (Task 5 step 4).
                return new ConfirmOutcome.StockRejected(item.sku(), item.quantity(), available);
            }
        }

        List<OrderItemRequest> orderItems = items.stream()
                .map(item -> new OrderItemRequest(item.sku(), item.quantity()))
                .toList();

        try {
            OrderPlacedView placed = orderRestClient.placeOrder(draft.getCustomerId(), orderItems, idempotencyKey);

            draft.setStatus(AssistantOrderDraftStatus.CONFIRMED);
            draft.setConfirmedOrderNumber(placed.orderNumber());
            draft.setUpdatedAt(Instant.now());
            draftRepository.save(draft);
            transitionSession(sessionId, AssistantSessionStatus.CONFIRMED);

            return new ConfirmOutcome.Confirmed(placed.orderNumber(), placed.rawResponseBody());
        } catch (OrderPlacementRejectedException ex) {
            // Draft remains WAITING_CONFIRMATION - the race window between step 4's re-check and
            // this call is exactly what ADR-0004's "Eventual Consistency Window" trade-off names;
            // a downstream 4xx here does not consume the draft, so the shopper can retry confirm.
            return new ConfirmOutcome.DownstreamRejected(ex.getStatusCode(), ex.getResponseBody());
        }
    }

    @Transactional
    public AssistantOrderDraftResult cancel(String sessionId, String draftId) {
        AssistantOrderDraft draft = requireDraft(sessionId, draftId);

        if (draft.getStatus() != AssistantOrderDraftStatus.WAITING_CONFIRMATION) {
            throw new DraftNotActionableException(draftId, draft.getStatus().name());
        }

        draft.setStatus(AssistantOrderDraftStatus.CANCELLED);
        draft.setUpdatedAt(Instant.now());
        draft = draftRepository.save(draft);
        transitionSession(sessionId, AssistantSessionStatus.CANCELLED);

        return new AssistantOrderDraftResult(draft, deserializeItems(draft.getItemsJson()));
    }

    /** A draft entity paired with its deserialized item snapshots, for building response DTOs. */
    public record AssistantOrderDraftResult(AssistantOrderDraft draft, List<DraftItemSnapshot> items) {
    }

    private AssistantOrderDraft requireDraft(String sessionId, String draftId) {
        return draftRepository.findByIdAndSessionId(draftId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        String.format("Order draft '%s' not found for this session.", draftId)));
    }

    private void transitionSession(String sessionId, AssistantSessionStatus status) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(status);
            session.setUpdatedAt(Instant.now());
            sessionRepository.save(session);
        });
    }

    private List<DraftItemSnapshot> deserializeItems(String itemsJson) {
        try {
            return objectMapper.readValue(itemsJson, new TypeReference<List<DraftItemSnapshot>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize draft items", e);
        }
    }
}
