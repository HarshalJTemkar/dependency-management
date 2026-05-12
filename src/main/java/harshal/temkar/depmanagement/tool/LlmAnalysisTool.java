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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tool that executes prompts against the primary LLM (Ollama) with a hard timeout,
 * automatically falling back to OpenAI if the primary times out or fails.
 *
 * <p>Timeout is enforced via {@link CompletableFuture#orTimeout} so a slow/hung
 * Ollama instance never blocks the pipeline for more than {@code app.ai.timeout-seconds}.</p>
 *
 * @author Harshal Temkar
 */
@Component
public class LlmAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(LlmAnalysisTool.class);

    private final ChatClient primaryClient;
    private final ChatClient fallbackClient;

    /** Hard wall-clock timeout per individual LLM call. */
    @Value("${app.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    /** Dedicated thread pool for async LLM calls (avoids stealing ForkJoinPool threads). */
    private final ExecutorService llmExecutor = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "llm-call");
        t.setDaemon(true);
        return t;
    });

    public LlmAnalysisTool(@Qualifier("ollamaChatClient") final ChatClient primaryClient,
                            @Qualifier("openAiChatClient") final ChatClient fallbackClient) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
    }

    /**
     * Executes a prompt against the primary LLM (Ollama), enforcing a hard timeout.
     * Falls back to OpenAI automatically if the primary times out or throws.
     *
     * @param prompt the prompt text to send
     * @return LLM response content
     * @throws RuntimeException if both providers fail
     */
    @Tool(description = "Execute a prompt against the primary LLM (Ollama). Falls back to OpenAI automatically.")
    public String executePrompt(final String prompt) {
        // Capture MDC here — this runs on a ForkJoinPool worker thread whose MDC is already set.
        final String correlationId = MDC.get(Constants.MDC_CORRELATION_ID);
        final String agentName     = MDC.get(Constants.MDC_AGENT_NAME);
        final Map<String, String> callerMdc = MDC.getCopyOfContextMap();

        log.info("[correlationId={}][agent={}] LLM prompt dispatched — provider=Ollama(primary), "
                + "promptChars={}, timeoutSeconds={}",
                correlationId, agentName, prompt.length(), timeoutSeconds);

        // ── Primary (Ollama) with enforced timeout ─────────────────────────────
        final long primaryStart = System.nanoTime();
        try {
            final String response = callWithTimeout(primaryClient, prompt, callerMdc,
                    "Ollama", correlationId, agentName);
            final long elapsedMs = (System.nanoTime() - primaryStart) / 1_000_000;
            log.info("[correlationId={}][agent={}] LLM primary (Ollama) COMPLETED — "
                    + "consumed={}ms, responseChars={}",
                    correlationId, agentName, elapsedMs,
                    response == null ? 0 : response.length());
            return response;

        } catch (final TimeoutException te) {
            final long elapsedMs = (System.nanoTime() - primaryStart) / 1_000_000;
            log.warn("[correlationId={}][agent={}] LLM primary (Ollama) TIMED OUT after {}ms "
                    + "(limit={}s) — falling back to OpenAI",
                    correlationId, agentName, elapsedMs, timeoutSeconds);

        } catch (final Exception pe) {
            final long elapsedMs = (System.nanoTime() - primaryStart) / 1_000_000;
            log.warn("[correlationId={}][agent={}] LLM primary (Ollama) FAILED after {}ms — "
                    + "{} — falling back to OpenAI",
                    correlationId, agentName, elapsedMs, pe.getMessage());
        }

        // ── Fallback (OpenAI) ──────────────────────────────────────────────────
        log.info("[correlationId={}][agent={}] LLM prompt dispatched — provider=OpenAI(fallback), "
                + "promptChars={}, timeoutSeconds={}",
                correlationId, agentName, prompt.length(), timeoutSeconds);
        final long fallbackStart = System.nanoTime();
        try {
            final String response = callWithTimeout(fallbackClient, prompt, callerMdc,
                    "OpenAI", correlationId, agentName);
            final long fbElapsedMs = (System.nanoTime() - fallbackStart) / 1_000_000;
            log.info("[correlationId={}][agent={}] LLM fallback (OpenAI) COMPLETED — "
                    + "consumed={}ms, responseChars={}",
                    correlationId, agentName, fbElapsedMs,
                    response == null ? 0 : response.length());
            return response;

        } catch (final TimeoutException te) {
            final long fbElapsedMs = (System.nanoTime() - fallbackStart) / 1_000_000;
            log.error("[correlationId={}][agent={}] LLM fallback (OpenAI) TIMED OUT after {}ms "
                    + "(limit={}s) — both LLMs unavailable",
                    correlationId, agentName, fbElapsedMs, timeoutSeconds);
            throw new RuntimeException("Both LLM providers timed out (Ollama + OpenAI)");

        } catch (final Exception fe) {
            final long fbElapsedMs = (System.nanoTime() - fallbackStart) / 1_000_000;
            log.error("[correlationId={}][agent={}] LLM fallback (OpenAI) FAILED after {}ms — "
                    + "both LLMs unavailable: {}",
                    correlationId, agentName, fbElapsedMs, fe.getMessage());
            throw (fe instanceof RuntimeException) ? (RuntimeException) fe : new RuntimeException(fe);
        }
    }

    /**
     * Submits an LLM call to a dedicated thread pool and enforces {@code timeoutSeconds}.
     * MDC is propagated into the worker thread so any logs inside the call are tagged.
     *
     * @param client        ChatClient to use
     * @param prompt        prompt text
     * @param mdcContext    caller's MDC snapshot to propagate
     * @param providerLabel label used in timeout log message
     * @param correlationId correlationId for logging
     * @param agentName     agentName for logging
     * @return LLM response content
     * @throws TimeoutException if the call exceeds {@code timeoutSeconds}
     * @throws Exception        if the LLM call throws
     */
    private String callWithTimeout(final ChatClient client,
                                    final String prompt,
                                    final Map<String, String> mdcContext,
                                    final String providerLabel,
                                    final String correlationId,
                                    final String agentName) throws Exception {
        final CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try {
                return client.prompt().user(prompt).call().content();
            } finally {
                MDC.clear();
            }
        }, llmExecutor).orTimeout(timeoutSeconds, TimeUnit.SECONDS);

        try {
            return future.get();
        } catch (final java.util.concurrent.ExecutionException ee) {
            final Throwable cause = ee.getCause();
            throw (cause instanceof Exception) ? (Exception) cause : new RuntimeException(cause);
        } catch (final java.util.concurrent.CancellationException ce) {
            throw new TimeoutException(providerLabel + " call was cancelled");
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(providerLabel + " call interrupted", ie);
        } catch (final Exception e) {
            // orTimeout wraps TimeoutException inside CompletionException
            if (e.getCause() instanceof TimeoutException) {
                throw (TimeoutException) e.getCause();
            }
            throw e;
        }
    }
}
