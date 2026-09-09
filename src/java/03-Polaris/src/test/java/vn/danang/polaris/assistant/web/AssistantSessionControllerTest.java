package vn.danang.polaris.assistant.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.dto.DraftItemDto;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantOrderDraft;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.assistant.service.AssistantSessionService;
import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;
import vn.danang.polaris.web.exception.ResourceNotFoundException;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(AssistantSessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class AssistantSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantSessionService sessionService;

    @MockitoBean
    private AssistantDraftService draftService;

    @MockitoBean
    private AssistantMessageRepository messageRepository;

    private AssistantSession createSampleSession(String sessionId, String userId, Long customerId) {
        AssistantSession session = new AssistantSession();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setCustomerId(customerId);
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setVersion(0L);
        return session;
    }

    @Test
    void createSession_authenticated_shouldReturn201AndLocationHeader() throws Exception {
        String sessionId = "sess-uuid-1234";
        AssistantSession sample = createSampleSession(sessionId, "user", 1L);

        when(sessionService.createSession(any(), eq(1L))).thenReturn(sample);

        String payload = """
            {
              "customerId": 1
            }
            """;

        mockMvc.perform(post("/api/v1/assistant/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/assistant/sessions/" + sessionId))
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.userId").value("user"))
                .andExpect(jsonPath("$.customerId").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void createSession_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSession_whenFound_shouldReturn200WithMessagesAndDraft() throws Exception {
        String sessionId = "sess-uuid-5678";
        AssistantSession session = createSampleSession(sessionId, "alice", 1L);

        AssistantMessage msg1 = new AssistantMessage();
        msg1.setId(10L);
        msg1.setSession(session);
        msg1.setRole(MessageRole.USER);
        msg1.setContent("Find fast chargers");
        msg1.setCreatedAt(Instant.now());

        AssistantMessage msg2 = new AssistantMessage();
        msg2.setId(11L);
        msg2.setSession(session);
        msg2.setRole(MessageRole.ASSISTANT);
        msg2.setContent("Found NG-CHARGER-01");
        msg2.setWidgetType("PRODUCT_CARD");
        msg2.setWidgetPayload("{\"sku\":\"NG-CHARGER-01\"}");
        msg2.setCreatedAt(Instant.now());

        AssistantOrderDraft draft = new AssistantOrderDraft();
        draft.setId("draft-uuid-9999");
        draft.setSession(session);
        draft.setCustomerId(1L);
        draft.setStatus(DraftStatus.WAITING_CONFIRMATION);
        draft.setTotalAmount(new BigDecimal("49.90"));
        draft.setExpiresAt(Instant.now().plusSeconds(900));
        draft.setCreatedAt(Instant.now());
        draft.setUpdatedAt(Instant.now());
        draft.setItems(List.of(
                new DraftItemDto("NG-CHARGER-01", "Nova Fast Charger", 2, new BigDecimal("24.95"), new BigDecimal("49.90"))
        ));

        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(msg1, msg2));
        when(draftService.getActiveDraft(sessionId)).thenReturn(Optional.of(draft));

        mockMvc.perform(get("/api/v1/assistant/sessions/" + sessionId)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.customerId").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].content").value("Find fast chargers"))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[1].widgetType").value("PRODUCT_CARD"))
                .andExpect(jsonPath("$.activeDraft").exists())
                .andExpect(jsonPath("$.activeDraft.id").value("draft-uuid-9999"))
                .andExpect(jsonPath("$.activeDraft.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.activeDraft.totalAmount").value(49.90))
                .andExpect(jsonPath("$.activeDraft.items", hasSize(1)))
                .andExpect(jsonPath("$.activeDraft.items[0].sku").value("NG-CHARGER-01"));
    }

    @Test
    void getSession_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        String sessionId = "sess-not-found";
        when(sessionService.getSession(sessionId))
                .thenThrow(new ResourceNotFoundException("Assistant session not found with id: " + sessionId));

        mockMvc.perform(get("/api/v1/assistant/sessions/" + sessionId)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"))
                .andExpect(jsonPath("$.detail").value("Assistant session not found with id: " + sessionId));
    }

    @Test
    void getSession_invalidIdFormat_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/sessions/invalid!id@here")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"));
    }

    @Test
    void getSession_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/sessions/sess-1234"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void closeSession_authenticated_shouldReturn204NoContent() throws Exception {
        String sessionId = "sess-to-close";
        AssistantSession closedSession = createSampleSession(sessionId, "alice", 1L);
        closedSession.setStatus(SessionStatus.CLOSED);

        when(sessionService.closeSession(sessionId)).thenReturn(closedSession);

        mockMvc.perform(delete("/api/v1/assistant/sessions/" + sessionId)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isNoContent());

        verify(sessionService).closeSession(sessionId);
    }

    @Test
    void closeSession_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        String sessionId = "sess-missing";
        when(sessionService.closeSession(sessionId))
                .thenThrow(new ResourceNotFoundException("Assistant session not found with id: " + sessionId));

        mockMvc.perform(delete("/api/v1/assistant/sessions/" + sessionId)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void closeSession_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(delete("/api/v1/assistant/sessions/sess-1234"))
                .andExpect(status().isUnauthorized());
    }
}
