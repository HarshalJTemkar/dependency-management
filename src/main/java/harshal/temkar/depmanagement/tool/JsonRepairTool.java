package harshal.temkar.depmanagement.tool;
import harshal.temkar.depmanagement.constants.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
/**
 * Tool that attempts to repair malformed JSON output from the LLM.
 * Uses the LLM itself to fix the JSON, up to a configurable max attempts.
 * @author Harshal Temkar
 */
@Component
public class JsonRepairTool {
    private static final Logger log = LoggerFactory.getLogger(JsonRepairTool.class);
    private final ChatClient primaryClient;
    private final ChatClient fallbackClient;
    public JsonRepairTool(@Qualifier("ollamaChatClient") final ChatClient pc, @Qualifier("openAiChatClient") final ChatClient fc){this.primaryClient=pc; this.fallbackClient=fc;}
    /**
     * Attempts to repair malformed JSON by sending it to the LLM with a fix instruction.
     * @param malformedJson the invalid JSON string to repair
     * @param attemptNumber current repair attempt (1-based)
     * @param maxAttempts   maximum repair attempts allowed
     * @return repaired JSON string, or original if repair failed
     */
    @Tool(description = "Attempt to repair malformed LLM JSON output. Sends the broken JSON back to the LLM for correction.")
    public String repairJson(final String malformedJson, final int attemptNumber, final int maxAttempts) {
        if (attemptNumber > maxAttempts) {
            log.warn("[correlationId={}][agent={}] JSON repair max attempts ({}) exceeded, returning original", MDC.get(Constants.MDC_CORRELATION_ID), MDC.get(Constants.MDC_AGENT_NAME), maxAttempts);
            return malformedJson;
        }
        final String repairPrompt = malformedJson + System.lineSeparator() + Constants.JSON_REPAIR_SUFFIX;
        log.info("[correlationId={}][agent={}] JSON repair attempt {}/{}", MDC.get(Constants.MDC_CORRELATION_ID), MDC.get(Constants.MDC_AGENT_NAME), attemptNumber, maxAttempts);
        try {
            return primaryClient.prompt().user(repairPrompt).call().content();
        } catch (final Exception ex) {
            log.warn("[correlationId={}] Repair via Ollama failed, trying OpenAI", MDC.get(Constants.MDC_CORRELATION_ID));
            return fallbackClient.prompt().user(repairPrompt).call().content();
        }
    }
}
