package vn.danang.polaris.assistant.model;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.tool.AssistantTool;

@Component
@ConditionalOnProperty(name = "polaris.ai.provider", havingValue = "cloud")
public class CloudModelClient implements AssistantModelClient {

    private final String apiKey;

    public CloudModelClient(@Value("${polaris.ai.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void streamChat(SessionContext context, List<AssistantTool> tools, ModelStreamListener listener) {
        if (apiKey == null || apiKey.isBlank()) {
            listener.onError("Cloud AI provider API key is unconfigured.", "Set polaris.ai.api-key or switch to polaris.ai.provider=local");
            listener.onDone("ERROR");
            return;
        }

        listener.onThought("Connecting to Cloud Foundation Model...");
        listener.onToken("Cloud AI integration active. Prompt received: " + context.userPrompt());
        listener.onDone("STOP");
    }
}
