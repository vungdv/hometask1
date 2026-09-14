package vn.danang.polaris.assistant.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

@Component
public class GeminiAiModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiModelClient.class);

    private final AssistantAiProperties aiModelConfig;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiAiModelClient(AssistantAiProperties aiModelConfig) {
        this(aiModelConfig, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper().findAndRegisterModules());
    }

    public GeminiAiModelClient(AssistantAiProperties aiModelConfig, HttpClient httpClient, ObjectMapper objectMapper) {
        this.aiModelConfig = aiModelConfig;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        String apiKey = aiModelConfig.getApiKey();
        if (apiKey == null) {
            throw new IllegalArgumentException("Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini.");
        }
        if (apiKey.isBlank() || (apiKey.startsWith("${") && apiKey.endsWith("}"))) {
            log.info("No AI API key configured; returning local assistant fallback.");
            return "I am Polaris Assistant! (Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini.)";
        }

        try {
            HttpRequest request = buildRequest(messages);
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return getMessage(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Gemini request interrupted", e);
            return "Request to AI model was interrupted.";
        } catch (IOException e) {
            log.error("Gemini request I/O error", e);
            return "Failed to communicate with AI Model: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            log.error("Invalid Gemini client configuration", e);
            return "Invalid AI model configuration.";
        } catch (RuntimeException e) {
            log.error("Unexpected error in Gemini client", e);
            return "Unexpected error communicating with AI Model.";
        }
    }

    private HttpRequest buildRequest(List<AssistantMessage> messages) throws IllegalArgumentException {
        String apiKey = aiModelConfig.getApiKey();
        if (aiModelConfig.getBaseUrl() == null || aiModelConfig.getBaseUrl().isBlank()
                || aiModelConfig.getModel() == null || aiModelConfig.getModel().isBlank()) {
            throw new IllegalArgumentException("AI model URL or model is not configured");
        }
        String message = getGeminiRequestMessage(messages);

        String url = aiModelConfig.getBaseUrl().replaceAll("/+$", "")
                + "/v1beta/models/" + aiModelConfig.getModel() + ":generateContent";
        
        log.info("Sending request to Gemini model {}", aiModelConfig.getModel());
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey).timeout(Duration.ofSeconds(aiModelConfig.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8))
                .build();
    }

    private String getGeminiRequestMessage(List<AssistantMessage> messages) {
        // Map the conversation history to the format expected by Gemini API
        List<Map<String, Object>> contents = 
            Optional.ofNullable(messages)
                .map(h -> 
                    h.stream()
                    .filter(msg -> msg.getContent() != null && !msg.getContent().isBlank())
                    .map(msg -> {
                        Map<String, Object> message = new HashMap<>();
                        // Gemini API expects "user" for user messages and "model" for assistant messages
                        // It doesn't use "assistant", "system", ... like other providers.
                        message.put("role", (msg.getRole() == null || msg.getRole() == MessageRole.USER) ? "user" : "model");
                        message.put("parts", List.of(Map.of("text", msg.getContent())));
                        return message;
                    })
                    .collect(Collectors.toList()))
                .orElse(new ArrayList<>());
        
        // What are the reason that we need provide system prompt per AI models over just one for all AI providers?
        // - System-message semantics differ between providers
        // - Models interpret instructions differently
        Map<String, Object> body = new HashMap<>();
        if (aiModelConfig.getSystemPrompt() != null && !aiModelConfig.getSystemPrompt().isBlank()) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", aiModelConfig.getSystemPrompt()))));
        }

        body.put("contents", contents);

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Gemini request body", e);
            throw new RuntimeException("Failed to serialize request body for Gemini API", e);
        }
    }

    private String getMessage(HttpResponse<String> response) throws IOException {
        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    String reply = parts.get(0).path("text").asText();
                    if (reply != null && !reply.isBlank()) {
                        return reply.trim();
                    }
                }
            }
            return "The AI Model returned an empty response.";
        } else {
            log.error("Gemini API error status: {} body: {}", response.statusCode(), response.body());
            String errorDetail = "Status " + response.statusCode();
            try {
                JsonNode errorNode = objectMapper.readTree(response.body()).path("error").path("message");
                if (!errorNode.isMissingNode() && !errorNode.asText().isBlank()) {
                    errorDetail = errorNode.asText();
                }
            } catch (Exception ignored) {
            }
            return "Unable to get response from AI Model (" + errorDetail + ").";
        }
    }
}
