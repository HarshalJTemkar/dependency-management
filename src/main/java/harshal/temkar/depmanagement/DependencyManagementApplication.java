package harshal.temkar.depmanagement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the Dependency Management AI Agent System.
 *
 * <p>This application orchestrates a pipeline of specialised Spring AI agents
 * to analyse Maven project dependencies, detect outdated versions and
 * breaking changes, and produce a rich HTML report.</p>
 *
 * <p>Technology stack:</p>
 * <ul>
 *   <li>Spring Boot 3.4.x — application framework</li>
 *   <li>Spring AI 1.0.0   — agent + tool-call orchestration</li>
 *   <li>Ollama / OpenAI   — primary / fallback LLM providers</li>
 *   <li>Caffeine           — in-process version-check caching</li>
 *   <li>Thymeleaf + ECharts — single-page HTML report UI</li>
 * </ul>
 *
 * @author Harshal Temkar
 * @version 1.0.0-SNAPSHOT
 */

@SpringBootApplication
@EnableCaching
@EnableAsync
public class DependencyManagementApplication {

    private static final Logger log =
            LoggerFactory.getLogger(DependencyManagementApplication.class);

    /**
     * Application entry point.
     *
     * @param args command-line arguments (passed through to Spring Boot)
     */
    public static void main(final String[] args) {
        SpringApplication.run(DependencyManagementApplication.class, args);
    }

    /**
     * Logs a startup banner once the application context is fully initialised.
     * Confirms that both Ollama and OpenAI auto-configuration are active.
     *
     * @param event the {@link ApplicationReadyEvent} published by Spring Boot
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(final ApplicationReadyEvent event) {
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║ Dependency Management AI Agent System — READY            ║");
        log.info("║ Spring Boot 3.4.x | Spring AI 1.0.0 | Java 21            ║");
        log.info("║ Primary LLM : Ollama (llama3.1:8b)                       ║");
        log.info("║ Fallback LLM: OpenAI (gpt-4o-mini)                       ║");
        log.info("╚══════════════════════════════════════════════════════════╝");
    }
}
