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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

@Component
public class GeminiAiModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiModelClient.class);

    private final AssistantAiProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiAiModelClient(AssistantAiProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper().findAndRegisterModules());
    }

    public GeminiAiModelClient(AssistantAiProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(List<AssistantMessage> conversationHistory, String latestMessage) {
        String apiKey = properties.getApiKey();
        if (apiKey == null) {
            throw new IllegalArgumentException("Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini.");
        }
        if (apiKey.isBlank() || (apiKey.startsWith("${") && apiKey.endsWith("}"))) {
            log.info("No AI API key configured; returning local assistant fallback.");
            return "I am Polaris Assistant! (Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini. Echo: \"" + latestMessage + "\")";
        }

        try {
            HttpRequest request = buildRequest(apiKey, conversationHistory, latestMessage);
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

    private HttpRequest buildRequest(String apiKey, List<AssistantMessage> history, String latestMessage)
            throws IOException {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                || properties.getModel() == null || properties.getModel().isBlank()) {
            throw new IllegalArgumentException("AI model URL or model is not configured");
        }

        List<Map<String, Object>> contents = new ArrayList<>();
        if (history != null) {
            for (AssistantMessage message : history) {
                if (message.getContent() != null && !message.getContent().isBlank()) {
                    contents.add(Map.of("role", message.getRole() == MessageRole.USER ? "user" : "model",
                            "parts", List.of(Map.of("text", message.getContent()))));
                }
            }
        }
        if (latestMessage != null && !latestMessage.isBlank()
                && (history == null || history.isEmpty()
                || history.get(history.size() - 1).getRole() != MessageRole.USER
                || !latestMessage.equals(history.get(history.size() - 1).getContent()))) {
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", latestMessage))));
        }
        Map<String, Object> body = new HashMap<>();
        if (properties.getSystemPrompt() != null && !properties.getSystemPrompt().isBlank()) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", properties.getSystemPrompt()))));
        }
        body.put("contents", contents);
        String url = properties.getBaseUrl().replaceAll("/+$", "")
                + "/v1beta/models/" + properties.getModel() + ":generateContent";
        log.info("Sending request to Gemini model {} ({} message turns)", properties.getModel(), contents.size());
        return HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey).timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
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
