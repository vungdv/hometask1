package vn.danang.polaris.outbox.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import vn.danang.polaris.outbox.IntegrationEventPublisher;
import vn.danang.polaris.outbox.OutboxIntegrationEventPublisher;
import vn.danang.polaris.outbox.store.JdbcOutboxStore;
import vn.danang.polaris.outbox.store.OutboxStore;

class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class));

    @Test
    void withDataSource_providesThePublisherAndStore() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class)).run(context -> {
            assertThat(context).hasSingleBean(IntegrationEventPublisher.class);
            assertThat(context.getBean(IntegrationEventPublisher.class)).isInstanceOf(OutboxIntegrationEventPublisher.class);
            assertThat(context.getBean(OutboxStore.class)).isInstanceOf(JdbcOutboxStore.class);
        });
    }

    @Test
    void withoutDataSource_backsOffWithoutFailing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IntegrationEventPublisher.class);
            assertThat(context).doesNotHaveBean(OutboxStore.class);
        });
    }

    @Test
    void disabledByProperty_backsOff() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("polaris.outbox.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IntegrationEventPublisher.class));
    }

    @Test
    void applicationBeans_win() {
        OutboxStore custom = record -> { };
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(OutboxStore.class, () -> custom)
                .run(context -> assertThat(context.getBean(OutboxStore.class)).isSameAs(custom));
    }
}
