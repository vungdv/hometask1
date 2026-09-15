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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.Tool;
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
        return generateResponse(messages, List.of()).text();
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        String apiKey = aiModelConfig.getApiKey();
        if (apiKey == null) {
            throw new IllegalArgumentException("Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini.");
        }
        if (apiKey.isBlank() || (apiKey.startsWith("${") && apiKey.endsWith("}"))) {
            log.info("No AI API key configured; returning local assistant fallback.");
            String lastUserMessage = (messages != null && !messages.isEmpty())
                    ? messages.stream()
                            .filter(msg -> msg.getRole() == null || msg.getRole() == MessageRole.USER)
                            .map(AssistantMessage::getContent)
                            .filter(content -> content != null && !content.isBlank())
                            .reduce((first, second) -> second)
                            .orElse("")
                    : "";
            return new ModelResponse("I am Polaris Assistant! (Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini. Echo: \"" + lastUserMessage + "\")");
        }

        try {
            HttpRequest request = buildRequest(messages, tools);
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseModelResponse(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Gemini request interrupted", e);
            return new ModelResponse("Request to AI model was interrupted.");
        } catch (IOException e) {
            log.error("Gemini request I/O error", e);
            return new ModelResponse("Failed to communicate with AI Model: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("Invalid Gemini client configuration", e);
            return new ModelResponse("Invalid AI model configuration.");
        } catch (RuntimeException e) {
            log.error("Unexpected error in Gemini client", e);
            return new ModelResponse("Unexpected error communicating with AI Model.");
        }
    }

    private HttpRequest buildRequest(List<AssistantMessage> messages, List<Tool> tools) throws IllegalArgumentException {
        String apiKey = aiModelConfig.getApiKey();
        if (aiModelConfig.getBaseUrl() == null || aiModelConfig.getBaseUrl().isBlank()
                || aiModelConfig.getModel() == null || aiModelConfig.getModel().isBlank()) {
            throw new IllegalArgumentException("AI model URL or model is not configured");
        }
        String payload = getGeminiRequestBody(messages, tools);

        String url = aiModelConfig.getBaseUrl().replaceAll("/+$", "")
                + "/v1beta/models/" + aiModelConfig.getModel() + ":generateContent";

        log.info("Sending request to Gemini model {}", aiModelConfig.getModel());
        return HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(aiModelConfig.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private String getGeminiRequestBody(List<AssistantMessage> messages, List<Tool> tools) {
        List<Map<String, Object>> contents = new ArrayList<>();
        if (messages != null) {
            for (AssistantMessage msg : messages) {
                if (msg.getRole() == MessageRole.USER || msg.getRole() == null) {
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        contents.add(Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", msg.getContent()))
                        ));
                    }
                } else if (msg.getRole() == MessageRole.ASSISTANT) {
                    if (msg.getToolCallId() != null && !msg.getToolCallId().isBlank()) {
                        Map<String, Object> functionCall = new HashMap<>();
                        functionCall.put("name", msg.getToolCallId());
                        functionCall.put("args", parseJsonMap(msg.getWidgetPayload()));
                        contents.add(Map.of(
                                "role", "model",
                                "parts", List.of(Map.of("functionCall", functionCall))
                        ));
                    } else if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        contents.add(Map.of(
                                "role", "model",
                                "parts", List.of(Map.of("text", msg.getContent()))
                        ));
                    }
                } else if (msg.getRole() == MessageRole.TOOL) {
                    String toolName = msg.getToolCallId() != null ? msg.getToolCallId() : "tool";
                    String toolContent = msg.getContent() != null ? msg.getContent() : "";
                    contents.add(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of(
                                    "functionResponse", Map.of(
                                            "name", toolName,
                                            "response", Map.of("result", toolContent)
                                    )
                            ))
                    ));
                }
            }
        }

        Map<String, Object> body = new HashMap<>();
        if (aiModelConfig.getSystemPrompt() != null && !aiModelConfig.getSystemPrompt().isBlank()) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", aiModelConfig.getSystemPrompt()))));
        }
        body.put("contents", contents);

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> functionDeclarations = tools.stream()
                    .map(this::toFunctionDeclaration)
                    .collect(Collectors.toList());
            body.put("tools", List.of(Map.of("functionDeclarations", functionDeclarations)));
        }

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Gemini request body", e);
            throw new RuntimeException("Failed to serialize request body for Gemini API", e);
        }
    }

    private Map<String, Object> toFunctionDeclaration(Tool tool) {
        Map<String, Object> decl = new HashMap<>();
        decl.put("name", tool.name());
        if (tool.description() != null && !tool.description().isBlank()) {
            decl.put("description", tool.description());
        }
        if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
            decl.put("parameters", tool.inputSchema());
        }
        return decl;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON map from widget payload: {}", json);
            return Map.of();
        }
    }

    private ModelResponse parseModelResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    List<ToolCall> toolCalls = new ArrayList<>();
                    StringBuilder textBuilder = new StringBuilder();

                    for (JsonNode part : parts) {
                        if (part.has("functionCall")) {
                            JsonNode fc = part.path("functionCall");
                            String fnName = fc.path("name").asText();
                            Map<String, Object> args = new HashMap<>();
                            if (fc.has("args") && fc.path("args").isObject()) {
                                args = objectMapper.convertValue(fc.path("args"), new TypeReference<Map<String, Object>>() {});
                            }
                            toolCalls.add(new ToolCall(fnName, args));
                        } else if (part.has("text")) {
                            String text = part.path("text").asText();
                            if (text != null && !text.isBlank()) {
                                if (!textBuilder.isEmpty()) {
                                    textBuilder.append(" ");
                                }
                                textBuilder.append(text.trim());
                            }
                        }
                    }

                    return new ModelResponse(textBuilder.toString(), toolCalls);
                }
            }
            return new ModelResponse("The AI Model returned an empty response.");
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
            return new ModelResponse("Unable to get response from AI Model (" + errorDetail + ").");
        }
    }
}
