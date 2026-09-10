package vn.danang.polaris.assistant.web;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.SessionStatus;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.service.AssistantDraftService;
import vn.danang.polaris.assistant.service.AssistantSessionService;
import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest({ChatViewController.class, AssistantSessionController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class WebChatClientIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantSessionService sessionService;

    @MockitoBean
    private AssistantDraftService draftService;

    @MockitoBean
    private AssistantMessageRepository messageRepository;

    @Test
    void chatView_unauthenticated_returns200AndForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/chat/index.html"));
    }

    @Test
    void chatView_trailingSlash_unauthenticated_returns200AndForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/chat/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/chat/index.html"));
    }

    @Test
    void assistantApi_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assistantApi_authenticated_returns201Created() throws Exception {
        AssistantSession session = new AssistantSession();
        session.setId("sess-test-123");
        session.setUserId("user");
        session.setStatus(SessionStatus.ACTIVE);

        when(sessionService.createSession(any(), any())).thenReturn(session);

        mockMvc.perform(post("/api/v1/assistant/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isCreated());
    }
}
