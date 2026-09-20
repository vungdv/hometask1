package vn.danang.polaris.assistant.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "assistant_messages")
@Getter
@Setter
public final class AssistantMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    private MessageRole role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "widget_type", length = 64)
    private String widgetType;

    @Column(name = "widget_payload", columnDefinition = "TEXT")
    private String widgetPayload;

    @Column(name = "tool_call_id", length = 64)
    private String toolCallId;

    @Column(name = "thought_signature", columnDefinition = "TEXT")
    private String thoughtSignature;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    public static AssistantMessage of(String messageText){
        var userMsg = new AssistantMessage();
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(messageText);
        userMsg.setCreatedAt(Instant.now());
        return userMsg;
    }

    public static AssistantMessage of(String message, MessageRole messageRole, String thoughtSignature) {
        var msg = new AssistantMessage();
        msg.setContent(message);
        msg.setRole(messageRole != null ? messageRole : MessageRole.USER);
        msg.setThoughtSignature(thoughtSignature);
        msg.setCreatedAt(Instant.now());
        return msg;
    }
}
