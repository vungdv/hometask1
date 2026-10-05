package vn.danang.polaris.assistant.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.resilience.CircuitBreakerConfiguration;
import vn.danang.polaris.assistant.resilience.CircuitBreakingIntentClassifier;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * End-to-end through {@code POST /api/v1/assistant/chat} with TypeSafe and Gemini stubbed over HTTP (WireMock):
 * the real classifier, model client and circuit breakers are wired as in production.
 * <ul>
 *   <li>TypeSafe down: the turn is answered as order status only, and the reply says so.</li>
 *   <li>TypeSafe and Gemini down: 503 Problem Details "temporarily down" with Retry-After.</li>
 *   <li>Breaker open: the provider is no longer called at all.</li>
 * </ul>
 */
@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AssistantCircuitBreakerIntegrationTest {

    private static final String TYPESAFE_PATH = "/v1/systemone";
    private static final String GEMINI_PATH = "/v1beta/models/gemini-test:generateContent";

    @RegisterExtension
    static WireMockExtension typeSafe = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension gemini = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("polaris.typesafe.api-key", () -> "test-typesafe-key");
        registry.add("polaris.typesafe.base-url", typeSafe::baseUrl);
        registry.add("polaris.ai.api-key", () -> "test-gemini-key");
        registry.add("polaris.ai.model", () -> "gemini-test");
        registry.add("polaris.ai.base-url", gemini::baseUrl);
        registry.add("polaris.assistant.resilience.circuit-breaker.minimum-number-of-calls", () -> "2");
        registry.add("polaris.assistant.resilience.circuit-breaker.sliding-window-size", () -> "2");
        registry.add("polaris.assistant.resilience.circuit-breaker.wait-duration-in-open-state", () -> "60s");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @BeforeEach
    void resetBreakers() {
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        typeSafe.resetAll();
        gemini.resetAll();
    }

    private static MockHttpServletRequestBuilder chat(String message) {
        return post("/api/v1/assistant/chat")
                .with(jwt().jwt(j -> j.subject("alice")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}");
    }

    private static void stubDown(WireMockExtension server, String path) {
        server.stubFor(WireMock.post(urlEqualTo(path)).willReturn(aResponse().withStatus(503)));
    }

    private static void stubGeminiReply(String text) {
        gemini.stubFor(WireMock.post(urlEqualTo(GEMINI_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"%s"}]}}]}
                        """.formatted(text))));
    }

    @Test
    @DisplayName("Given TypeSafe is down, when chatting, then 200 with the order-status-only notice ahead of the model's reply")
    void typesafe_down_pins_order_status() throws Exception {
        stubDown(typeSafe, TYPESAFE_PATH);
        stubGeminiReply("Please share your order number.");

        mockMvc.perform(chat("find me a charger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(CircuitBreakingIntentClassifier.DEGRADED_NOTICE + "\n\nPlease share your order number."));

        typeSafe.verify(1, postRequestedFor(urlEqualTo(TYPESAFE_PATH)));
        gemini.verify(1, postRequestedFor(urlEqualTo(GEMINI_PATH)));
    }

    @Test
    @DisplayName("Given TypeSafe and Gemini are down, when chatting, then 503 Problem Details 'temporarily down' with Retry-After")
    void both_down_returns_503() throws Exception {
        stubDown(typeSafe, TYPESAFE_PATH);
        stubDown(gemini, GEMINI_PATH);

        mockMvc.perform(chat("where is my order ORD-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(AssistantUnavailableExceptionHandler.TYPE))
                .andExpect(jsonPath("$.detail").value("Polaris Assistant is temporarily down. Please come back later."));
    }

    @Test
    @DisplayName("Given repeated failures opened both breakers, when chatting, then neither provider is called and the turn is 503")
    void open_breakers_short_circuit() throws Exception {
        stubDown(typeSafe, TYPESAFE_PATH);
        stubDown(gemini, GEMINI_PATH);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(chat("where is my order")).andExpect(status().isServiceUnavailable());
        }
        assertThat(circuitBreakers.circuitBreaker(CircuitBreakerConfiguration.TYPESAFE).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreakers.circuitBreaker(CircuitBreakerConfiguration.GEMINI).getState()).isEqualTo(CircuitBreaker.State.OPEN);

        mockMvc.perform(chat("where is my order")).andExpect(status().isServiceUnavailable());

        typeSafe.verify(2, postRequestedFor(urlEqualTo(TYPESAFE_PATH)));
        gemini.verify(2, postRequestedFor(urlEqualTo(GEMINI_PATH)));
    }
}
