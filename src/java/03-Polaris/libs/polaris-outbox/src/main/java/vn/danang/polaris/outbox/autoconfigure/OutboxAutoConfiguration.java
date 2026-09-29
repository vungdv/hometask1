package vn.danang.polaris.outbox.autoconfigure;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.outbox.IntegrationEventPublisher;
import vn.danang.polaris.outbox.OutboxIntegrationEventPublisher;
import vn.danang.polaris.outbox.store.JdbcOutboxStore;
import vn.danang.polaris.outbox.store.OutboxStore;

/**
 * Wires the outbox publisher only where the application already has a single {@link DataSource}:
 * apps without one get no beans and no failure. Opt out with {@code polaris.outbox.enabled=false}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration" })
@ConditionalOnClass({ JdbcClient.class, PlatformTransactionManager.class })
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnBooleanProperty(name = "polaris.outbox.enabled", matchIfMissing = true)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxStore outboxStore(DataSource dataSource) {
        return new JdbcOutboxStore(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    IntegrationEventPublisher integrationEventPublisher(OutboxStore outboxStore, ObjectProvider<JsonMapper> jsonMapper) {
        return new OutboxIntegrationEventPublisher(outboxStore, jsonMapper.getIfAvailable(JsonMapper::new));
    }
}
