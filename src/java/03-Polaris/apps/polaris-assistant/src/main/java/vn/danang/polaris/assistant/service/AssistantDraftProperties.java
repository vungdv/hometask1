package vn.danang.polaris.assistant.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Externalized order-draft TTL, so {@link DraftStagingService}'s insert path and WO-021's
 * expiration sweep read the same configured value rather than each hard-coding "15 minutes".
 */
@ConfigurationProperties(prefix = "polaris.assistant.draft")
@Getter
@Setter
public class AssistantDraftProperties {

    private Duration ttl = Duration.ofMinutes(15);
}
