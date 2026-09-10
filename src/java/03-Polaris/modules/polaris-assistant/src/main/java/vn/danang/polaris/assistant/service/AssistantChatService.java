package vn.danang.polaris.assistant.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.model.AssistantModelClient;

@Service
@Transactional
public class AssistantChatService {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatService.class);
    private final AssistantModelClient modelClient;

    public AssistantChatService(
            AssistantModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public ChatMessageResponse sendMessage(ChatMessageRequest request, String userId) {
        String messageText = request.resolvedMessage();
        if (messageText == null || messageText.isBlank()) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }

        // 2. Save user message to database
        AssistantMessage userMsg = new AssistantMessage();
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(messageText);
        userMsg.setCreatedAt(Instant.now());

        // 4. Forward message to AI Model
        log.info("Forwarding chat message to AI Model for userId: {}, message: {}", userId, messageText);
        String reply = modelClient.chat(List.of(userMsg), messageText);

        // 5. Save assistant reply to database
        AssistantMessage assistantMsg = new AssistantMessage();
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent(reply);
        assistantMsg.setCreatedAt(Instant.now());


        return new ChatMessageResponse(
                "fake-session-id", 
                MessageRole.ASSISTANT.name(),
                reply,
                assistantMsg.getCreatedAt()
        );
    }
}
