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
        if (apiKey == null || apiKey.isBlank()) {
            log.info("No AI API key configured; returning local assistant fallback.");
            return "I am Polaris Assistant! (Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini. Echo: \"" + latestMessage + "\")";
        }

        try {
            String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
            String url = String.format("%s/v1beta/models/%s:generateContent?key=%s",
                    baseUrl, properties.getModel(), apiKey);

            Map<String, Object> requestBody = new HashMap<>();

            // System instruction
            if (properties.getSystemPrompt() != null && !properties.getSystemPrompt().isBlank()) {
                requestBody.put("system_instruction", Map.of(
                        "parts", List.of(Map.of("text", properties.getSystemPrompt()))
                ));
            }

            // Build contents from conversation history
            List<Map<String, Object>> contents = new ArrayList<>();
            if (conversationHistory != null) {
                for (AssistantMessage msg : conversationHistory) {
                    if (msg.getContent() == null || msg.getContent().isBlank()) {
                        continue;
                    }
                    String role = (msg.getRole() == MessageRole.USER) ? "user" : "model";
                    contents.add(Map.of(
                            "role", role,
                            "parts", List.of(Map.of("text", msg.getContent()))
                    ));
                }
            }

            // If history didn't already include the latest message, append it
            boolean latestIncluded = false;
            if (conversationHistory != null && !conversationHistory.isEmpty()) {
                AssistantMessage last = conversationHistory.get(conversationHistory.size() - 1);
                if (last.getRole() == MessageRole.USER && latestMessage.equals(last.getContent())) {
                    latestIncluded = true;
                }
            }
            if (!latestIncluded && latestMessage != null && !latestMessage.isBlank()) {
                contents.add(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", latestMessage))
                ));
            }

            requestBody.put("contents", contents);
            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            log.info("Sending request to Gemini model {} ({} message turns)", properties.getModel(), contents.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

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
                } catch (Exception ignored) {}
                return "Unable to get response from AI Model (" + errorDetail + ").";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Gemini request interrupted", e);
            return "Request to AI model was interrupted.";
        } catch (IOException e) {
            log.error("Gemini request I/O error", e);
            return "Failed to communicate with AI Model: " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error in Gemini client", e);
            return "Unexpected error communicating with AI Model.";
        }
    }
}
