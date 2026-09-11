package vn.danang.polaris.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "polaris.ai")
@Getter
@Setter
public class AssistantAiProperties {

    private String provider = "gemini";
    private String apiKey = "";
    private String model = "gemini-3.6-flash";
    private String baseUrl = "https://generativelanguage.googleapis.com";
    private String systemPrompt = "You are Polaris Assistant, a friendly and helpful AI assistant for the Polaris store. Help users answer questions and navigate the store politely and concisely.";
    private int timeoutSeconds = 30;
}
