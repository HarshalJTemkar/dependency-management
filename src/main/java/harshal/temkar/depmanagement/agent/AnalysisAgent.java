package harshal.temkar.depmanagement.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.domain.enums.Severity;
import harshal.temkar.depmanagement.domain.model.AnalysisResult;
import harshal.temkar.depmanagement.domain.model.VersionCheckResult;
import harshal.temkar.depmanagement.exception.AgentException;
import harshal.temkar.depmanagement.tool.JsonRepairTool;
import harshal.temkar.depmanagement.tool.LlmAnalysisTool;
import harshal.temkar.depmanagement.util.RuleBasedAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Value("${app.ai.iterations.analysis.batch-size:10}")
    private int batchSize;

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

        try {
            final List<AnalysisResult> results = new ArrayList<>();

            // Split into batches for Pass 1
            final List<List<VersionCheckResult>> batches = splitBatches(versionCheckResults);
            int pass = 1;

            for (final List<VersionCheckResult> batch : batches) {
                MDC.put(Constants.MDC_ITERATION_PASS, String.valueOf(pass));
                log.debug("[correlationId={}][agent={}][pass={}] Analyzing batch of {} deps",
                        MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, pass, batch.size());

                final List<AnalysisResult> batchResults = analyzeBatch(batch, pass);
                results.addAll(batchResults);
            }

            log.info("[correlationId={}][agent={}] Analysis COMPLETE — {} results",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, results.size());
            MDC.remove(Constants.MDC_ITERATION_PASS);
            return results;

        } catch (final Exception ex) {
            log.warn("[correlationId={}][agent={}] LLM analysis failed ({}), falling back to RuleBasedAnalyzer",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_ANALYSIS, ex.getMessage());
            MDC.remove(Constants.MDC_ITERATION_PASS);
            return ruleBasedAnalyzer.analyze(versionCheckResults);
        }
    }

    /**
     * Analyzes a single batch of version-check results via LLM.
     *
     * @param batch list of results in this batch
     * @param pass  current iteration pass number
     * @return list of analysis results for this batch
     */
    private List<AnalysisResult> analyzeBatch(final List<VersionCheckResult> batch, final int pass) {
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
     * Splits the full dependency list into fixed-size batches.
     *
     * @param list full list
     * @return list of sub-lists
     */
    private List<List<VersionCheckResult>> splitBatches(final List<VersionCheckResult> list) {
        final List<List<VersionCheckResult>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
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
