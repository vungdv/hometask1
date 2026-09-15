package vn.danang.polaris.assistant.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.model.AssistantModelClient;

@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
class FakeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @Test
    @DisplayName("GET /api/v1/assistant/demo/issue-1-thread-affinity demonstrates token loss on thread hop and preservation via context propagation")
    void demonstrateIssue1_withJwtAuthentication_showsThreadAffinityLossAndRemediation() throws Exception {
        String testBearerToken = "ey-user-test-bearer-token-999";

        mockMvc.perform(get("/api/v1/assistant/demo/issue-1-thread-affinity")
                        .with(jwt().jwt(jwt -> jwt.tokenValue(testBearerToken).claim("sub", "user-alice"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 1. Verify caller thread retains token
                .andExpect(jsonPath("$.issue").value("1. UserContext.resolveBearerToken() Thread-Affinity Loss"))
                .andExpect(jsonPath("$.callerThread.token").value(testBearerToken))
                // 2. Verify CompletableFuture async boundary drops token
                .andExpect(jsonPath("$.asyncThreadHop.token").value("null"))
                .andExpect(jsonPath("$.asyncThreadHop.isTokenLost").value(true))
                // 3. Verify Virtual Thread executor dispatch drops token
                .andExpect(jsonPath("$.virtualThreadHop.token").value("null"))
                .andExpect(jsonPath("$.virtualThreadHop.isTokenLost").value(true))
                // 4. Verify remediation via DelegatingSecurityContextExecutorService preserves token
                .andExpect(jsonPath("$.remediationWithContextPropagation.tokenPreserved").value(testBearerToken))
                // 5. Verify risk analysis is included
                .andExpect(jsonPath("$.riskAnalysis").exists());
    }

    @Test
    @DisplayName("GET /api/v1/assistant/demo/issue-1-thread-affinity when unauthenticated returns null for caller token")
    void demonstrateIssue1_unauthenticated_returnsNullTokens() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/demo/issue-1-thread-affinity"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.callerThread.token").value("null"))
                .andExpect(jsonPath("$.asyncThreadHop.token").value("null"))
                .andExpect(jsonPath("$.virtualThreadHop.token").value("null"));
    }
}
