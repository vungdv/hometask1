package vn.danang.polaris.outbox.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.outbox.IntegrationEvent;
import vn.danang.polaris.outbox.OutboxIntegrationEventPublisher;

/** V15 must stay portable to the local H2 default, like V13/V14. PostgreSQL is covered by the integration test. */
class OutboxMigrationH2Test {

    @Test
    void migrationsApplyOnH2_andAnEventCanBeRecorded_butNotInAReadOnlyTransaction() {
        // One long-lived session, like the app's connection pool: H2 binds CHECK expressions to the creating session.
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:h2:mem:outbox-" + UUID.randomUUID(), "sa", "", true);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        OutboxIntegrationEventPublisher publisher =
                new OutboxIntegrationEventPublisher(new JdbcOutboxStore(jdbc), new JsonMapper());
        UUID id = new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status ->
                publisher.publish(new IntegrationEvent("t.v1", "/s", "d", "k-1", java.util.Map.of("a", 1))));

        assertThat(jdbc.sql("SELECT payload FROM outbox_events WHERE event_id = ?").param(id)
                .query(String.class).single()).isEqualTo("{\"a\":1}");
        assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE event_id = ?").param(id)
                .query(String.class).single()).isEqualTo("PENDING");

        // H2 treats Connection.setReadOnly as a hint, so only the publisher's check stops a read-only recording here.
        TransactionTemplate readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status ->
                publisher.publish(new IntegrationEvent("t.v1", "/s", "d", "k-2", java.util.Map.of("a", 2)))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events").query(Long.class).single()).isEqualTo(1L);
    }
}
