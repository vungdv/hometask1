package vn.danang.polaris.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import vn.danang.polaris.assistant.dto.AssistantDraftResponse;
import vn.danang.polaris.assistant.dto.DraftItemDto;
import vn.danang.polaris.assistant.engine.AgencyOrchestrator;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.model.AssistantModelClient;
import vn.danang.polaris.assistant.model.ModelEvent;
import vn.danang.polaris.assistant.model.ModelStreamListener;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.tool.AssistantToolRegistry;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

class AgencyOrchestratorTest {

    private AssistantModelClient modelClient;
    private AssistantToolRegistry toolRegistry;
    private AssistantSessionRepository sessionRepository;
    private AssistantMessageRepository messageRepository;
    private ExecutorService assistantExecutor;

    private AgencyOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        modelClient = mock(AssistantModelClient.class);
        toolRegistry = mock(AssistantToolRegistry.class);
        sessionRepository = mock(AssistantSessionRepository.class);
        messageRepository = mock(AssistantMessageRepository.class);
        assistantExecutor = new DelegatingSecurityContextExecutorService(Executors.newVirtualThreadPerTaskExecutor());

        orchestrator = new AgencyOrchestrator(
                modelClient,
                toolRegistry,
                sessionRepository,
                messageRepository,
                assistantExecutor
        );

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void processUserMessage_sessionNotFound_throwsResourceNotFoundException() {
        when(sessionRepository.findById("non-existent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.processUserMessage("non-existent", "hello", event -> {}))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");

        verify(messageRepository, never()).save(any());
        verify(modelClient, never()).streamChat(any(), any(), any());
    }

    @Test
    void processUserMessage_closedSession_emitsErrorAndHalts() {
        AssistantSession session = new AssistantSession();
        session.setId("closed-sess");
        session.setStatus(SessionStatus.CLOSED);

        when(sessionRepository.findById("closed-sess")).thenReturn(Optional.of(session));

        List<ModelEvent> captured = new ArrayList<>();
        orchestrator.processUserMessage("closed-sess", "hello", captured::add);

        assertThat(captured).isNotEmpty();
        assertThat(captured.stream().anyMatch(e -> e instanceof ModelEvent.ErrorEvent err && err.error().contains("CLOSED"))).isTrue();
        assertThat(captured.get(captured.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("ERROR"));

        verify(messageRepository, never()).save(any());
        verify(modelClient, never()).streamChat(any(), any(), any());
    }

    @Test
    void processUserMessage_success_persistsUserTurnAndAssistantTurn() {
        AssistantSession session = new AssistantSession();
        session.setId("sess-1");
        session.setUserId("user-1");
        session.setCustomerId(100L);
        session.setStatus(SessionStatus.ACTIVE);

        when(sessionRepository.findById("sess-1")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("sess-1")).thenReturn(List.of());

        doAnswer(invocation -> {
            ModelStreamListener listener = invocation.getArgument(2);
            listener.onThought("Thinking about search...");
            listener.onToken("Here is the ");
            listener.onToken("product.");
            listener.onWidget("PRODUCT_CARD", Map.of("sku", "NG-EARBUD-01", "name", "Earbuds"));
            listener.onDone("STOP");
            return null;
        }).when(modelClient).streamChat(any(), any(), any());

        List<ModelEvent> capturedEvents = new ArrayList<>();
        orchestrator.processUserMessage("sess-1", "Find earbuds", capturedEvents::add);

        // 1. Verify user message saved
        verify(messageRepository).save(argThat(msg ->
                msg.getRole() == MessageRole.USER && "Find earbuds".equals(msg.getContent())
        ));

        // 2. Verify assistant message saved with concatenated tokens and widget payload
        verify(messageRepository).save(argThat(msg ->
                msg.getRole() == MessageRole.ASSISTANT &&
                "Here is the product.".equals(msg.getContent()) &&
                "PRODUCT_CARD".equals(msg.getWidgetType()) &&
                msg.getWidgetPayload() != null &&
                msg.getWidgetPayload().contains("NG-EARBUD-01")
        ));

        // 3. Verify session updated
        verify(sessionRepository).save(argThat(s -> "sess-1".equals(s.getId()) && s.getUpdatedAt() != null));

        // 4. Verify listener events
        assertThat(capturedEvents).hasSize(5);
    }

    @Test
    void processUserMessage_draftEvent_persistsDraftCardWidget() {
        AssistantSession session = new AssistantSession();
        session.setId("sess-1");
        session.setUserId("user-1");
        session.setCustomerId(100L);
        session.setStatus(SessionStatus.ACTIVE);

        when(sessionRepository.findById("sess-1")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("sess-1")).thenReturn(List.of());

        DraftItemDto item = new DraftItemDto("NG-EARBUD-01", "Earbuds", 1, new BigDecimal("49.99"), new BigDecimal("49.99"));
        AssistantDraftResponse draft = new AssistantDraftResponse(
                "draft-1", "sess-1", 100L, "WAITING_CONFIRMATION", List.of(item), new BigDecimal("49.99"), null, null, null, null
        );

        doAnswer(invocation -> {
            ModelStreamListener listener = invocation.getArgument(2);
            listener.onDraft(draft);
            listener.onToken("Draft staged.");
            listener.onDone("STOP");
            return null;
        }).when(modelClient).streamChat(any(), any(), any());

        List<ModelEvent> capturedEvents = new ArrayList<>();
        orchestrator.processUserMessage("sess-1", "Stage draft", capturedEvents::add);

        verify(messageRepository).save(argThat(msg ->
                msg.getRole() == MessageRole.ASSISTANT &&
                "DRAFT_CARD".equals(msg.getWidgetType()) &&
                msg.getWidgetPayload() != null &&
                msg.getWidgetPayload().contains("draft-1")
        ));
    }

    @Test
    void processUserMessage_modelException_emitsErrorEvent() {
        AssistantSession session = new AssistantSession();
        session.setId("sess-1");
        session.setStatus(SessionStatus.ACTIVE);

        when(sessionRepository.findById("sess-1")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("sess-1")).thenReturn(List.of());

        doAnswer(invocation -> {
            throw new RuntimeException("Model inference timeout");
        }).when(modelClient).streamChat(any(), any(), any());

        List<ModelEvent> capturedEvents = new ArrayList<>();
        orchestrator.processUserMessage("sess-1", "Hi", capturedEvents::add);

        assertThat(capturedEvents.stream().anyMatch(e -> e instanceof ModelEvent.ErrorEvent err && err.error().contains("Model inference timeout"))).isTrue();
        assertThat(capturedEvents.get(capturedEvents.size() - 1)).isEqualTo(new ModelEvent.DoneEvent("ERROR"));
    }

    @Test
    void processUserMessageAsync_propagatesSecurityContext() {
        AssistantSession session = new AssistantSession();
        session.setId("sess-async");
        session.setUserId("user-security-1");
        session.setCustomerId(200L);
        session.setStatus(SessionStatus.ACTIVE);

        when(sessionRepository.findById("sess-async")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("sess-async")).thenReturn(List.of());

        // Setup security context on the caller thread
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "user-security-1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
        SecurityContextHolder.setContext(context);

        AtomicReference<String> asyncPrincipal = new AtomicReference<>();

        doAnswer(invocation -> {
            // Check the SecurityContext in the async worker thread!
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                asyncPrincipal.set(auth.getName());
            }
            ModelStreamListener listener = invocation.getArgument(2);
            listener.onToken("Async response");
            listener.onDone("STOP");
            return null;
        }).when(modelClient).streamChat(any(), any(), any());

        CompletableFuture<Void> future = orchestrator.processUserMessageAsync("sess-async", "Async message", event -> {});
        future.join();

        assertThat(asyncPrincipal.get()).isEqualTo("user-security-1");
        verify(messageRepository).save(argThat(msg -> msg.getRole() == MessageRole.USER));
        verify(messageRepository).save(argThat(msg -> msg.getRole() == MessageRole.ASSISTANT && "Async response".equals(msg.getContent())));
    }
}
