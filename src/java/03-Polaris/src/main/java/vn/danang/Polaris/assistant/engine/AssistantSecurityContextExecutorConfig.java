package vn.danang.polaris.assistant.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

@Configuration
public class AssistantSecurityContextExecutorConfig {

    @Bean(name = "assistantExecutor")
    public ExecutorService assistantExecutor() {
        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        return new DelegatingSecurityContextExecutorService(virtualThreadExecutor);
    }
}
