package vn.danang.polaris.assistant.ai;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

@Component
public class GeminiAiModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiModelClient.class);

    private final AssistantAiProperties aiModelConfig;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public GeminiAiModelClient(AssistantAiProperties aiModelConfig, ObjectProvider<Tracer> tracerProvider) {
        this(aiModelConfig, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper().findAndRegisterModules(),
                tracerProvider != null ? tracerProvider.getIfAvailable() : null);
    }

    public GeminiAiModelClient(AssistantAiProperties aiModelConfig) {
        this(aiModelConfig, (Tracer) null);
    }

    public GeminiAiModelClient(AssistantAiProperties aiModelConfig, @Nullable Tracer tracer) {
        this(aiModelConfig, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper().findAndRegisterModules(), tracer);
    }

    public GeminiAiModelClient(AssistantAiProperties aiModelConfig, HttpClient httpClient, ObjectMapper objectMapper) {
        this(aiModelConfig, httpClient, objectMapper, null);
    }

    public GeminiAiModelClient(AssistantAiProperties aiModelConfig, HttpClient httpClient, ObjectMapper objectMapper, @Nullable Tracer tracer) {
        this.aiModelConfig = aiModelConfig;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        return generateResponse(messages, List.of()).text();
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        return generateResponse(messages, tools, ModelRequestContext.empty());
    }

    @Override
    @CustomNextSpan(
            name = "gemini.generate_content",
            tags = {
                    @SpanTag(key = "gen_ai.system", value = "gemini"),
                    @SpanTag(key = "gen_ai.operation.name", value = "chat"),
                    @SpanTag(key = "gen_ai.client", value = "GeminiAiModelClient"),
                    @SpanTag(key = "peer.service", value = "generativelanguage.googleapis.com"),
                    @SpanTag(key = "gemini.tools.count", expression = "#tools != null && !#tools.isEmpty() ? #tools.size() : null"),
                    @SpanTag(key = "agent.iteration", expression = "#context?.iteration()"),
                    @SpanTag(key = "agent.intent_id", expression = "#context != null ? (#context.intentId() ?: 'unknown') : null"),
                    @SpanTag(key = "agent.intent_confidence", expression = "#context?.intentConfidence()"),
                    @SpanTag(key = "agent.tools_offered_count", expression = "#context?.toolsOfferedCount()")
            }
    )
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools, @Nullable ModelRequestContext context) {
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

        Span span = this.tracer != null ? this.tracer.currentSpan() : null;
        if (span != null) {
            String model = Optional.ofNullable(aiModelConfig.getModel()).filter(s -> !s.isBlank()).orElse("unknown");
            span.tag("gen_ai.request.model", model);
        }
        return executeAndParse(messages, tools, span);
    }

    private ModelResponse executeAndParse(List<AssistantMessage> messages, List<Tool> tools, @Nullable Span span) {
        try {
            HttpRequest request = buildRequest(messages, tools, span);
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseModelResponse(response, span);
        } catch (InterruptedException e) {
            recordSpanException(span, e);
            Thread.currentThread().interrupt();
            log.error("Gemini request interrupted", e);
            return new ModelResponse("Request to AI model was interrupted.");
        } catch (IOException e) {
            recordSpanException(span, e);
            log.error("Gemini request I/O error", e);
            return new ModelResponse("Failed to communicate with AI Model: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            recordSpanException(span, e);
            log.error("Invalid Gemini client configuration", e);
            return new ModelResponse("Invalid AI model configuration.");
        } catch (RuntimeException e) {
            recordSpanException(span, e);
            log.error("Unexpected error in Gemini client", e);
            return new ModelResponse("Unexpected error communicating with AI Model.");
        }
    }

    private void recordSpanException(@Nullable Span span, Throwable e) {
        if (span != null) {
            span.error(e);
            span.tag("error", "true");
            span.tag("error.type", e.getClass().getSimpleName());
        }
    }

    private HttpRequest buildRequest(List<AssistantMessage> messages, List<Tool> tools, @Nullable Span span) throws IllegalArgumentException {
        String apiKey = aiModelConfig.getApiKey();
        if (aiModelConfig.getBaseUrl() == null || aiModelConfig.getBaseUrl().isBlank()
                || aiModelConfig.getModel() == null || aiModelConfig.getModel().isBlank()) {
            throw new IllegalArgumentException("AI model URL or model is not configured");
        }
        String payload = getGeminiRequestBody(messages, tools);

        String url = aiModelConfig.getBaseUrl().replaceAll("/+$", "")
                + "/v1beta/models/" + aiModelConfig.getModel() + ":generateContent";

        log.info("Sending request to Gemini model {}", aiModelConfig.getModel());
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(aiModelConfig.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

        if (span != null && span.context() != null) {
            String traceId = span.context().traceId();
            String spanId = span.context().spanId();
            if (traceId != null && spanId != null) {
                builder.header("traceparent", "00-" + traceId + "-" + spanId + "-01");
            }
        }

        return builder.build();
    }

    private String getGeminiRequestBody(List<AssistantMessage> messages, List<Tool> tools) {
        List<Map<String, Object>> contents = new ArrayList<>();
        if (messages != null) {
            for (AssistantMessage msg : messages) {
                if (msg.getRole() == MessageRole.USER || msg.getRole() == null) {
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        addContentPart(contents, "user", Map.of("text", msg.getContent()));
                    }
                } else if (msg.getRole() == MessageRole.ASSISTANT) {
                    if (msg.getToolCallId() != null && !msg.getToolCallId().isBlank()) {
                        Map<String, Object> functionCall = new HashMap<>();
                        functionCall.put("name", msg.getToolCallId());
                        functionCall.put("args", parseJsonMap(msg.getWidgetPayload()));

                        Map<String, Object> part = new HashMap<>();
                        part.put("functionCall", functionCall);

                        String sig = msg.getThoughtSignature();
                        if (sig != null && !sig.isBlank()) {
                            part.put("thoughtSignature", sig);
                        } else if (isFirstFunctionCallInCurrentModelTurn(contents)) {
                            // Gemini 3/2.5 requires thoughtSignature on functionCall parts.
                            // If missing, use the official bypass signature to prevent 400 validation error.
                            part.put("thoughtSignature", "skip_thought_signature_validator");
                        }
                        addContentPart(contents, "model", part);
                    } else if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        Map<String, Object> part = new HashMap<>();
                        part.put("text", msg.getContent());
                        if (msg.getThoughtSignature() != null && !msg.getThoughtSignature().isBlank()) {
                            part.put("thoughtSignature", msg.getThoughtSignature());
                        }
                        addContentPart(contents, "model", part);
                    }
                } else if (msg.getRole() == MessageRole.TOOL) {
                    String toolName = msg.getToolCallId() != null ? msg.getToolCallId() : "tool";
                    String toolContent = msg.getContent() != null ? msg.getContent() : "";
                    Map<String, Object> part = Map.of(
                            "functionResponse", Map.of(
                                    "name", toolName,
                                    "response", Map.of("result", toolContent)
                            )
                    );
                    addContentPart(contents, "user", part);
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

    @SuppressWarnings("unchecked")
    private boolean isFirstFunctionCallInCurrentModelTurn(List<Map<String, Object>> contents) {
        if (contents.isEmpty()) {
            return true;
        }
        Map<String, Object> lastContent = contents.get(contents.size() - 1);
        if (!"model".equals(lastContent.get("role"))) {
            return true;
        }
        List<Map<String, Object>> parts = (List<Map<String, Object>>) lastContent.get("parts");
        if (parts == null || parts.isEmpty()) {
            return true;
        }
        for (Map<String, Object> p : parts) {
            if (p.containsKey("functionCall")) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void addContentPart(List<Map<String, Object>> contents, String role, Map<String, Object> part) {
        if (!contents.isEmpty()) {
            Map<String, Object> last = contents.get(contents.size() - 1);
            if (role.equals(last.get("role"))) {
                List<Map<String, Object>> parts = (List<Map<String, Object>>) last.get("parts");
                if (parts != null) {
                    parts.add(part);
                    return;
                }
            }
        }
        Map<String, Object> entry = new HashMap<>();
        entry.put("role", role);
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(part);
        entry.put("parts", parts);
        contents.add(entry);
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

    private ModelResponse parseModelResponse(HttpResponse<String> response, @Nullable Span span) throws IOException {
        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());

            if (span != null && root.has("usageMetadata")) {
                JsonNode usage = root.path("usageMetadata");
                if (usage.has("promptTokenCount")) {
                    String promptTokens = usage.path("promptTokenCount").asText();
                    span.tag("gen_ai.usage.input_tokens", promptTokens);
                }
                if (usage.has("candidatesTokenCount")) {
                    String completionTokens = usage.path("candidatesTokenCount").asText();
                    span.tag("gen_ai.usage.output_tokens", completionTokens);
                }
                if (usage.has("totalTokenCount")) {
                    span.tag("gen_ai.usage.total_tokens", usage.path("totalTokenCount").asText());
                }
            }

            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    List<ToolCall> toolCalls = new ArrayList<>();
                    StringBuilder textBuilder = new StringBuilder();
                    String responseThoughtSignature = null;

                    for (JsonNode part : parts) {
                        String partSignature = null;
                        if (part.hasNonNull("thoughtSignature")) {
                            partSignature = part.path("thoughtSignature").asText();
                        } else if (part.hasNonNull("thought_signature")) {
                            partSignature = part.path("thought_signature").asText();
                        }

                        if (part.has("functionCall")) {
                            JsonNode fc = part.path("functionCall");
                            String id = fc.hasNonNull("id") ? fc.path("id").asText() : null;
                            String fnName = fc.path("name").asText();
                            Map<String, Object> args = new HashMap<>();
                            if (fc.has("args") && fc.path("args").isObject()) {
                                args = objectMapper.convertValue(fc.path("args"), new TypeReference<Map<String, Object>>() {});
                            } else if (fc.has("arguments") && fc.path("arguments").isObject()) {
                                args = objectMapper.convertValue(fc.path("arguments"), new TypeReference<Map<String, Object>>() {});
                            }
                            if (partSignature == null) {
                                if (fc.hasNonNull("thoughtSignature")) {
                                    partSignature = fc.path("thoughtSignature").asText();
                                } else if (fc.hasNonNull("thought_signature")) {
                                    partSignature = fc.path("thought_signature").asText();
                                }
                            }
                            toolCalls.add(new ToolCall(id, fnName, args, partSignature));
                        } else {
                            boolean isThought = part.path("thought").asBoolean(false);
                            if (!isThought && part.has("text")) {
                                String text = part.path("text").asText();
                                if (text != null && !text.isBlank()) {
                                    if (!textBuilder.isEmpty()) {
                                        textBuilder.append(" ");
                                    }
                                    textBuilder.append(text.trim());
                                }
                            }
                            if (partSignature != null) {
                                responseThoughtSignature = partSignature;
                            }
                        }
                    }

                    if (span != null) {
                        if (!toolCalls.isEmpty()) {
                            span.tag("gemini.tool_calls.count", String.valueOf(toolCalls.size()));
                            span.tag("gen_ai.response.finish_reason", "tool_calls");
                        } else {
                            span.tag("gen_ai.response.finish_reason", "stop");
                        }
                    }

                    return new ModelResponse(textBuilder.toString(), toolCalls, responseThoughtSignature);
                }
            }
            if (span != null) {
                span.tag("gen_ai.response.finish_reason", "stop");
            }
            return new ModelResponse("The AI Model returned an empty response.");
        } else {
            if (span != null) {
                span.tag("http.status_code", String.valueOf(response.statusCode()));
                span.tag("error", "true");
            }
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
