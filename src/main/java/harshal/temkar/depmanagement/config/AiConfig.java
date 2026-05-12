package harshal.temkar.depmanagement.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.util.List;

/**
 * Spring AI configuration.
 * <p>Registers two {@link ChatClient} beans:
 * <ul>
 *   <li>{@code ollamaChatClient} (primary) backed by local Ollama llama3.1:8b</li>
 *   <li>{@code openAiChatClient} (fallback) backed by OpenAI gpt-4o-mini</li>
 * </ul>
 * Also exposes {@link AppAiProperties} for iteration configuration.
 *
 * @author Harshal Temkar
 */
@Configuration
@EnableConfigurationProperties(AiConfig.AppAiProperties.class)
public class AiConfig {

    /**
     * Primary {@link ChatClient} backed by Ollama.
     *
     * @param ollamaChatModel auto-configured Ollama chat model
     * @return Ollama-backed ChatClient
     */
    @Bean
    @Primary
    @Qualifier("ollamaChatClient")
    public ChatClient ollamaChatClient(final OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }

    /**
     * Fallback {@link ChatClient} backed by OpenAI.
     *
     * @param openAiChatModel auto-configured OpenAI chat model
     * @return OpenAI-backed ChatClient
     */
    @Bean
    @Qualifier("openAiChatClient")
    public ChatClient openAiChatClient(final OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
    }

    // ── Strongly-typed configuration properties ──────────────────────────────

    /**
     * Binds {@code app.ai.*} from application.yml into a strongly-typed record.
     *
     * @param primaryProvider  name of the primary LLM provider
     * @param fallbackProvider name of the fallback LLM provider
     * @param maxRetries       global retry cap
     * @param timeoutSeconds   LLM call timeout
     * @param iterations       per-agent iteration caps
     */
    @ConfigurationProperties(prefix = "app.ai")
    public record AppAiProperties(
        String primaryProvider,
        String fallbackProvider,
        int maxRetries,
        int timeoutSeconds,
        Iterations iterations
    ) {

        /** Nested iteration configuration record. */
        public record Iterations(
            Parser parser,
            VersionChecker versionChecker,
            Analysis analysis,
            ReportBuilder reportBuilder,
            Orchestrator orchestrator
        ) {}

        /** Parser agent iteration settings. */
        public record Parser(int maxPasses, String confidenceThreshold) {}

        /** Version-checker agent iteration settings. */
        public record VersionChecker(int maxPasses, boolean excludePrerelease, boolean excludeSnapshot) {}

        /** Analysis agent iteration settings. */
        public record Analysis(int maxPasses, int batchSize, List<String> deepAnalysisSeverities, int jsonRepairMaxAttempts) {}

        /** Report-builder agent iteration settings. */
        public record ReportBuilder(int maxPasses) {}

        /** Orchestrator retry/backoff settings. */
        public record Orchestrator(int maxRetries, int backoffInitialSeconds, int backoffMultiplier) {}
    }
}
