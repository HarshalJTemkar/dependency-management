package harshal.temkar.depmanagement.tool;
import harshal.temkar.depmanagement.constants.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** Tool: primary(Ollama) then fallback(OpenAI) LLM execution. @author Harshal Temkar */
@Component
public class LlmAnalysisTool {
    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisTool.class);
    private final ChatClient primaryClient;
    private final ChatClient fallbackClient;
    @Value("${app.ai.timeout-seconds}")
    private int timeoutSeconds;
    public LlmAnalysisTool(@Qualifier("ollamaChatClient") final ChatClient pc, @Qualifier("openAiChatClient") final ChatClient fc){this.primaryClient=pc; this.fallbackClient=fc;}
    /** Execute prompt via primary LLM, auto-fallback to OpenAI. @param prompt text @return response */
    @Tool(description = "Execute a prompt against the primary LLM (Ollama). Falls back to OpenAI automatically.")
    public String executePrompt(final String prompt) {
        log.debug("[correlationId={}][agent={}] Executing LLM prompt ({} chars)", MDC.get(Constants.MDC_CORRELATION_ID), MDC.get(Constants.MDC_AGENT_NAME), prompt.length());
        try { return callLlm(primaryClient, prompt); }
        catch (final Exception pe) {
            log.warn("[correlationId={}][agent={}] Ollama unavailable, falling back to OpenAI: {}", MDC.get(Constants.MDC_CORRELATION_ID), MDC.get(Constants.MDC_AGENT_NAME), pe.getMessage());
            try { return callLlm(fallbackClient, prompt); }
            catch (final Exception fe) {
                log.error("[correlationId={}][agent={}] Both LLMs failed: {}", MDC.get(Constants.MDC_CORRELATION_ID), MDC.get(Constants.MDC_AGENT_NAME), fe.getMessage());
                throw fe;
            }
        }
    }
    private String callLlm(final ChatClient c, final String p){return c.prompt().user(p).call().content();}
}
