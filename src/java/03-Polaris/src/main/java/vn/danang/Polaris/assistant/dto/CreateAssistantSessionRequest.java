package vn.danang.polaris.assistant.dto;

public record CreateAssistantSessionRequest(
    String userId,
    Long customerId
) {}
