package vn.danang.polaris.assistant.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.mcp.PolarisMcpClient;
import vn.danang.polaris.assistant.model.AssistantModelClient;

@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
class AssistantOpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @Test
    @DisplayName("GET /v3/api-docs returns 200 OK with Polaris AI Assistant OpenAPI specification")
    void getOpenApiDocs_shouldReturnValidOpenApiSpec() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("Polaris AI Assistant API")))
                .andExpect(content().string(containsString("/api/v1/assistant/chat")))
                .andExpect(content().string(containsString("keycloak-auth2-codeflow")))
                .andExpect(content().string(containsString("application/problem+json")));
    }
}
