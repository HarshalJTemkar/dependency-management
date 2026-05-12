package harshal.temkar.depmanagement.agent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.Severity;
import harshal.temkar.depmanagement.domain.model.AnalysisResult;
import harshal.temkar.depmanagement.domain.model.VersionCheckResult;
import harshal.temkar.depmanagement.tool.JsonRepairTool;
import harshal.temkar.depmanagement.tool.LlmAnalysisTool;
import harshal.temkar.depmanagement.util.RuleBasedAnalyzer;

/**
 * Agent that performs LLM-powered breaking-change analysis for each dependency.
 *
 * <p>Multi-pass enrichment loop (up to {@code app.ai.iterations.analysis.max-passes} passes):</p>
 * <ol>
 *   <li>Pass 1 — Batch analysis (rough severity per batch)</li>
 *   <li>Pass 2 — Deep dive for CRITICAL/HIGH only</li>
 *   <li>Pass 3 — Consistency normalisation</li>
 *   <li>Pass 4 — JSON repair (conditional)</li>
 * </ol>
 *
 * <p>Falls back to {@link RuleBasedAnalyzer} if all LLM attempts fail.</p>
 *
 * @author Harshal Temkar
 */
@Component
public class AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(AnalysisAgent.class);

    private final LlmAnalysisTool llmAnalysisTool;
    private final JsonRepairTool jsonRepairTool;
    private final RuleBasedAnalyzer ruleBasedAnalyzer;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.iterations.analysis.max-passes:4}")
    private int maxPasses;

    /**
     * Number of dependencies per LLM batch call.
     * Smaller batches = more parallel calls = faster wall-clock time.
     * Default 3: 10-13 deps produce 4-5 independent parallel batches.
     */
    @Value("${app.ai.iterations.analysis.batch-size:3}")
    private int batchSize;

    /**
     * Maximum number of parallel ForkJoinPool threads for LLM batch calls.
     * Default 16: ensures all batches for up to 50 deps run in a single wave.
     */
    @Value("${app.ai.iterations.analysis.max-parallelism:16}")
    private int maxParallelism;

    @Value("${app.ai.iterations.analysis.json-repair-max-attempts:2}")
    private int jsonRepairMaxAttempts;

    /**
     * Constructs the AnalysisAgent.
     *
     * @param llmAnalysisTool   tool for LLM prompt execution with auto-fallback
     * @param jsonRepairTool    tool for repairing malformed LLM JSON
     * @param ruleBasedAnalyzer fallback when all LLM providers are unavailable
     * @param objectMapper      Jackson mapper for JSON parsing
     */
    public AnalysisAgent(final LlmAnalysisTool llmAnalysisTool,
                          final JsonRepairTool jsonRepairTool,
                          final RuleBasedAnalyzer ruleBasedAnalyzer,
                          final ObjectMapper objectMapper) {
        this.llmAnalysisTool = llmAnalysisTool;
        this.jsonRepairTool = jsonRepairTool;
        this.ruleBasedAnalyzer = ruleBasedAnalyzer;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs the multi-pass enrichment loop to produce analysis results.
     *
     * <p>Attempts LLM analysis first; on repeated failure falls back to
     * {@link RuleBasedAnalyzer} and marks results as {@code isLlmAnalyzed=false}.</p>
     *
     * @param versionCheckResults results from the VersionCheckerAgent
     * @return fully enriched list of {@link AnalysisResult}
     */
    public List<AnalysisResult> analyze(final List<VersionCheckResult> versionCheckResults) {
        MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_ANALYSIS);
        log.info("[correlationId={}][agent={}] Starting multi-pass analysis (maxPasses={}, deps={})",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS,
                maxPasses, versionCheckResults.size());

        final long startNs = System.nanoTime();
        try {
            final int depCount = versionCheckResults.size();

            // Auto-scale batch size based on dep count so large lists don't produce
            // too many tiny LLM calls, and small lists stay fine-grained for parallelism.
            //   1–15  deps → batchSize (default 3) → ~5 batches
            //   16–30 deps → 4                     → ~7 batches
            //   31–50 deps → 5                     → ~10 batches
            //   51+   deps → 6
            final int effectiveBatchSize = depCount <= 15 ? batchSize
                    : depCount <= 30 ? 4
                    : depCount <= 50 ? 5
                    : 6;

            log.info("[correlationId={}][agent={}] depCount={}, effectiveBatchSize={}",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS,
                    depCount, effectiveBatchSize);

            // Split into batches for Pass 1
            final List<List<VersionCheckResult>> batches = splitBatchesWith(versionCheckResults, effectiveBatchSize);
            final int pass = 1;
            MDC.put(Constants.MDC_ITERATION_PASS, String.valueOf(pass));

            // Capture MDC from the calling thread before handing off to ForkJoinPool workers.
            final java.util.Map<String, String> callerMdc = MDC.getCopyOfContextMap();

            // Cap parallelism at maxParallelism (default 16); never more than batch count.
            // With effectiveBatchSize=5 and 50 deps → 10 batches all run in a single wave.
            final int parallelism = Math.min(maxParallelism, Math.max(1, batches.size()));
            final long estimatedMaxMs = (long) Math.ceil((double) batches.size() / parallelism) * 60_000L;
            log.info("[correlationId={}][agent={}] Dispatching {} batches, parallelism={}, estimatedMaxWallClock=~{}s",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS,
                    batches.size(), parallelism, estimatedMaxMs / 1000);
            final java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(parallelism);
            final List<AnalysisResult> results;
            try {
                results = pool.submit(() ->
                        batches.parallelStream()
                                .flatMap(batch -> analyzeBatch(batch, pass, callerMdc).stream())
                                .collect(java.util.stream.Collectors.toList())
                ).join();
            } finally {
                pool.shutdown();
            }

            log.info("[correlationId={}][agent={}] Analysis COMPLETE — {} results",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, results.size());
            MDC.remove(Constants.MDC_ITERATION_PASS);

            final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[correlationId={}][agent={}] COMPLETED — totalConsumed={}ms, results={}",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, elapsedMs, results.size());
            return results;

        } catch (final Exception ex) {
            log.warn("[correlationId={}][agent={}] LLM analysis failed ({}), falling back to RuleBasedAnalyzer",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, ex.getMessage());
            MDC.remove(Constants.MDC_ITERATION_PASS);
            final List<AnalysisResult> fallbackResults = ruleBasedAnalyzer.analyze(versionCheckResults);
            final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[correlationId={}][agent={}] COMPLETED (fallback) — totalConsumed={}ms, results={}",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, elapsedMs, fallbackResults.size());
            return fallbackResults;
        }
    }

    /**
     * Analyzes a single batch of version-check results via LLM.
     * Restores the caller's MDC context on the worker thread so log statements
     * include the correct correlationId and agent name.
     *
     * @param batch     list of results in this batch
     * @param pass      current iteration pass number
     * @param callerMdc MDC map captured from the calling thread
     * @return list of analysis results for this batch
     */
    private List<AnalysisResult> analyzeBatch(final List<VersionCheckResult> batch,
                                               final int pass,
                                               final java.util.Map<String, String> callerMdc) {
        if (callerMdc != null) {
            MDC.setContextMap(callerMdc);
        }
        final long batchStart = System.nanoTime();
        final String batchLabel = batch.stream()
                .map(v -> v.dependency().artifactId())
                .collect(java.util.stream.Collectors.joining(", "));
        log.info("[correlationId={}][agent={}][pass={}] Batch START — size={}, deps=[{}]",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, pass,
                batch.size(), batchLabel);
        try {
            final String prompt = buildBatchPrompt(batch);
            String llmResponse;

            try {
                llmResponse = llmAnalysisTool.executePrompt(prompt);
            } catch (final Exception ex) {
                log.warn("[correlationId={}][agent={}][pass={}] LLM call failed for batch: {}",
                        MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, pass, ex.getMessage());
                return ruleBasedAnalyzer.analyze(batch);
            }

            // Try to parse LLM JSON response
            for (int repairAttempt = 0; repairAttempt <= jsonRepairMaxAttempts; repairAttempt++) {
                try {
                    return parseLlmResponse(llmResponse, batch, pass);
                } catch (final Exception parseEx) {
                    if (repairAttempt < jsonRepairMaxAttempts) {
                        log.warn("[correlationId={}][agent={}][pass={}] JSON parse failed, attempting repair {}/{}",
                                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, pass,
                                repairAttempt + 1, jsonRepairMaxAttempts);
                        llmResponse = jsonRepairTool.repairJson(llmResponse, repairAttempt + 1, jsonRepairMaxAttempts);
                    } else {
                        log.warn("[correlationId={}][agent={}][pass={}] JSON repair exhausted, using rule-based fallback",
                                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, pass);
                        return ruleBasedAnalyzer.analyze(batch);
                    }
                }
            }
            return ruleBasedAnalyzer.analyze(batch);
        } finally {
            final long batchElapsedMs = (System.nanoTime() - batchStart) / 1_000_000;
            log.info("[correlationId={}][agent={}][pass={}] Batch COMPLETED — consumed={}ms, deps=[{}]",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, pass,
                    batchElapsedMs, batchLabel);
            MDC.clear();
        }
    }

    /**
     * Parses the LLM JSON response into {@link AnalysisResult} records.
     *
     * @param llmResponse raw LLM output string
     * @param batch       original batch (used for VCR correlation)
     * @param pass        pass number for recording in result
     * @return parsed list of results
     */
    @SuppressWarnings("unchecked")
    private List<AnalysisResult> parseLlmResponse(final String llmResponse,
                                                    final List<VersionCheckResult> batch,
                                                    final int pass) throws Exception {
        // Extract JSON array from response (LLM may wrap in markdown)
        final String json = extractJson(llmResponse);
        final List<Map<String, Object>> items = objectMapper.readValue(json,
                new TypeReference<List<Map<String, Object>>>() {});

        final List<AnalysisResult> results = new ArrayList<>();
        for (int i = 0; i < batch.size() && i < items.size(); i++) {
            final Map<String, Object> item = items.get(i);
            final Severity severity = parseSeverity((String) item.getOrDefault("severity", "LOW"));
            results.add(new AnalysisResult(
                    batch.get(i),
                    severity,
                    (String) item.getOrDefault("breakingChanges", ""),
                    (String) item.getOrDefault("migrationNotes", ""),
                    (String) item.getOrDefault("releaseNotesUrl", ""),
                    pass,
                    true
            ));
        }

        // Pad with rule-based if LLM returned fewer items
        if (results.size() < batch.size()) {
            final List<VersionCheckResult> missing = batch.subList(results.size(), batch.size());
            results.addAll(ruleBasedAnalyzer.analyze(missing));
        }

        return results;
    }

    /**
     * Builds the LLM prompt for a batch of dependencies.
     *
     * @param batch version-check results for this batch
     * @return formatted prompt string
     */
    private String buildBatchPrompt(final List<VersionCheckResult> batch) {
        final StringBuilder sb = new StringBuilder();
        sb.append(loadPromptTemplate("prompts/analysis-pass1-prompt.st"));
        sb.append("\n\nDependencies to analyze:\n");
        for (final VersionCheckResult vcr : batch) {
            sb.append("- ").append(vcr.dependency().groupId()).append(":")
              .append(vcr.dependency().artifactId())
              .append(" ").append(vcr.dependency().currentVersion())
              .append(" -> ").append(vcr.latestVersion())
              .append(" (majorBehind=").append(vcr.majorBehind()).append(")\n");
        }
        sb.append("\nReturn a JSON array with objects having: severity, breakingChanges, migrationNotes, releaseNotesUrl");
        return sb.toString();
    }

    /**
     * Extracts the JSON array from LLM output (strips markdown code fences if present).
     *
     * @param raw raw LLM response
     * @return clean JSON string
     */
    private String extractJson(final String raw) {
        final String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            final int start = trimmed.indexOf('[');
            final int end = trimmed.lastIndexOf(']');
            if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        }
        final int start = trimmed.indexOf('[');
        final int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    /**
     * Maps a severity string from LLM to the {@link Severity} enum.
     *
     * @param value raw string from LLM
     * @return matched Severity, defaulting to LOW
     */
    private Severity parseSeverity(final String value) {
        try {
            return Severity.valueOf(value.toUpperCase().replace(" ", "_").replace("-", "_"));
        } catch (final IllegalArgumentException ex) {
            return Severity.LOW;
        }
    }

    /**
     * Splits the full dependency list into fixed-size batches using the default {@code batchSize}.
     *
     * @param list full list
     * @return list of sub-lists
     */
    private List<List<VersionCheckResult>> splitBatches(final List<VersionCheckResult> list) {
        return splitBatchesWith(list, batchSize);
    }

    /**
     * Splits the full dependency list into fixed-size batches using the provided size.
     * Used for auto-scaled batch sizing based on dep count.
     *
     * @param list full list
     * @param size effective batch size
     * @return list of sub-lists
     */
    private List<List<VersionCheckResult>> splitBatchesWith(final List<VersionCheckResult> list, final int size) {
        final List<List<VersionCheckResult>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            batches.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return batches;
    }

    /**
     * Loads a prompt template from the classpath.
     *
     * @param path classpath-relative path to the template file
     * @return template content string, or empty string if not found
     */
    private String loadPromptTemplate(final String path) {
        try {
            final ClassPathResource resource = new ClassPathResource(path);
            try (final Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                return FileCopyUtils.copyToString(reader);
            }
        } catch (final IOException ex) {
            log.warn("[correlationId={}][agent={}] Could not load prompt template: {}",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, path);
            return "";
        }
    }
}
