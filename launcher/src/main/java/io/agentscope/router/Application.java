package io.agentscope.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the AgentScope Router service.
 *
 * Scans the {@code io.agentscope.router} package tree so that beans defined in
 * the {@code common}, {@code llm}, {@code business} and {@code api} modules are
 * picked up automatically.
 *
 * <p>{@link EnableScheduling} activates {@code @Scheduled} on the
 * {@link io.agentscope.router.business.multimodal.VideoTaskWorker} poller
 * and any future periodic jobs.
 */
@SpringBootApplication(scanBasePackages = "io.agentscope.router")
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
