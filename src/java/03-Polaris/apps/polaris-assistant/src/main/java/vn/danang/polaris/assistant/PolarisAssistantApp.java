package vn.danang.polaris.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "vn.danang.polaris.assistant",
        "vn.danang.polaris.config",
        "vn.danang.polaris.web.exception"
})
@ConfigurationPropertiesScan(basePackages = "vn.danang.polaris.assistant")
@EnableScheduling
public class PolarisAssistantApp {

    public static void main(String[] args) {
        SpringApplication.run(PolarisAssistantApp.class, args);
    }
}
