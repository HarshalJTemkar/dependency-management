package harshal.temkar.depmanagement.agent;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.domain.model.AnalysisResult;
import harshal.temkar.depmanagement.domain.model.DependencyInfo;
import harshal.temkar.depmanagement.domain.model.VersionCheckResult;
import harshal.temkar.depmanagement.dto.AnalysisRequestDTO;
import harshal.temkar.depmanagement.dto.DependencyReportDTO;
import harshal.temkar.depmanagement.exception.AgentException;
import harshal.temkar.depmanagement.util.CorrelationIdUtil;
import harshal.temkar.depmanagement.util.RuleBasedAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Master coordinator agent for the full dependency analysis pipeline.
 *
 * <p>Invokes agents in sequence via direct method calls (the Spring AI Tool Call
 * abstraction is used within agents themselves). Applies a retry loop with
 * exponential back-off for each agent step.  On per-agent failure, the pipeline
 * continues with fallback data and sets {@code hasPartialResults=true}.</p>
 *
 * <p>Retry configuration is driven by {@code app.ai.iterations.orchestrator.*}.</p>
 *
 * @author Harshal Temkar
 */
@Component
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);

    private final SourceReaderAgent sourceReaderAgent;
    private final DependencyParserAgent dependencyParserAgent;
    private final VersionCheckerAgent versionCheckerAgent;
    private final AnalysisAgent analysisAgent;
    private final ReportBuilderAgent reportBuilderAgent;
    private final RuleBasedAnalyzer ruleBasedAnalyzer;

    @Value("${app.ai.iterations.orchestrator.max-retries:3}")
    private int maxRetries;

    @Value("${app.ai.iterations.orchestrator.backoff-initial-seconds:1}")
    private long backoffInitialSeconds;

    @Value("${app.ai.iterations.orchestrator.backoff-multiplier:2}")
    private long backoffMultiplier;

    /**
     * Constructs the OrchestratorAgent with all required sub-agents.
     *
     * @param sourceReaderAgent     agent for reading pom.xml
     * @param dependencyParserAgent agent for parsing pom.xml
     * @param versionCheckerAgent   agent for version lookup
     * @param analysisAgent         agent for LLM analysis
     * @param reportBuilderAgent    agent for report assembly
     * @param ruleBasedAnalyzer     fallback for analysis
     */
    public OrchestratorAgent(final SourceReaderAgent sourceReaderAgent,
                              final DependencyParserAgent dependencyParserAgent,
                              final VersionCheckerAgent versionCheckerAgent,
                              final AnalysisAgent analysisAgent,
                              final ReportBuilderAgent reportBuilderAgent,
                              final RuleBasedAnalyzer ruleBasedAnalyzer) {
        this.sourceReaderAgent = sourceReaderAgent;
        this.dependencyParserAgent = dependencyParserAgent;
        this.versionCheckerAgent = versionCheckerAgent;
        this.analysisAgent = analysisAgent;
        this.reportBuilderAgent = reportBuilderAgent;
        this.ruleBasedAnalyzer = ruleBasedAnalyzer;
    }

    /**
     * Runs the complete multi-agent dependency analysis pipeline.
     *
     * @param request validated analysis request from the controller
     * @return assembled dependency report DTO
     */
    public DependencyReportDTO orchestrate(final AnalysisRequestDTO request) {
        final String correlationId = CorrelationIdUtil.resolveOrGenerate(request.correlationId());
        MDC.put(Constants.MDC_CORRELATION_ID, correlationId);
        MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_ORCHESTRATOR);

        log.info("[correlationId={}][agent={}] Pipeline START — sourceType={}",
                correlationId, Constants.AGENT_ORCHESTRATOR, request.sourceType());

        boolean hasPartialResults = false;
        final String sourceInfo = resolveSourceInfo(request);

        // Step 1 — Source reading
        final String pomXml = executeWithRetry(
                Constants.AGENT_SOURCE_READER, correlationId,
                () -> sourceReaderAgent.readPomXml(
                        request.sourceType(), request.repoUrl(),
                        request.localPath(), request.githubToken()));

        // Step 2 — Dependency parsing
        List<DependencyInfo> dependencies;
        try {
            dependencies = executeWithRetry(
                    Constants.AGENT_PARSER, correlationId,
                    () -> dependencyParserAgent.parse(pomXml));
        } catch (final Exception ex) {
            log.error("[correlationId={}] DependencyParserAgent failed after retries: {}",
                    correlationId, ex.getMessage());
            throw new AgentException(ErrorCode.AGENT_FAILED,
                    "Dependency parsing failed: " + ex.getMessage(),
                    Constants.AGENT_PARSER, 0, ex);
        }

        // Step 3 — Version check
        List<VersionCheckResult> versionResults;
        try {
            versionResults = executeWithRetry(
                    Constants.AGENT_VERSION, correlationId,
                    () -> versionCheckerAgent.checkVersions(dependencies));
        } catch (final Exception ex) {
            log.warn("[correlationId={}] VersionCheckerAgent failed — using UNKNOWN versions: {}",
                    correlationId, ex.getMessage());
            versionResults = dependencies.stream()
                    .map(d -> new VersionCheckResult(d, "UNKNOWN", false, false, 0, 0, 0, 0))
                    .toList();
            hasPartialResults = true;
        }

        // Step 4 — Analysis (with internal fallback in AnalysisAgent)
        final boolean[] partialFlag = {hasPartialResults};
        final List<VersionCheckResult> finalVersionResults = versionResults;
        List<AnalysisResult> analysisResults;
        try {
            analysisResults = executeWithRetry(
                    Constants.AGENT_ANALYSIS, correlationId,
                    () -> analysisAgent.analyze(finalVersionResults));
            final boolean anyRuleBased = analysisResults.stream().anyMatch(r -> !r.isLlmAnalyzed());
            if (anyRuleBased) {
                partialFlag[0] = true;
                log.warn("[correlationId={}] Some results used RuleBasedAnalyzer fallback", correlationId);
            }
        } catch (final Exception ex) {
            log.warn("[correlationId={}] AnalysisAgent failed — using rule-based fallback: {}",
                    correlationId, ex.getMessage());
            analysisResults = ruleBasedAnalyzer.analyze(finalVersionResults);
            partialFlag[0] = true;
        }

        // Step 5 — Report assembly
        final List<AnalysisResult> finalAnalysisResults = analysisResults;
        final boolean finalPartial = partialFlag[0];
        final DependencyReportDTO report = executeWithRetry(
                Constants.AGENT_REPORT, correlationId,
                () -> reportBuilderAgent.buildReport(
                        correlationId, sourceInfo, finalAnalysisResults, finalPartial));

        log.info("[correlationId={}][agent={}] Pipeline COMPLETE — {} deps, partialResults={}",
                correlationId, Constants.AGENT_ORCHESTRATOR, report.totalDependencies(), finalPartial);
        MDC.clear();
        return report;
    }

    /**
     * Executes a pipeline step with configurable retry and exponential back-off.
     *
     * @param agentName     agent label for logging
     * @param correlationId tracing ID
     * @param action        supplier wrapping the step logic
     * @param <T>           return type of the step
     * @return result from the action
     * @throws AgentException if all retries exhausted
     */
    private <T> T executeWithRetry(final String agentName,
                                    final String correlationId,
                                    final java.util.function.Supplier<T> action) {
        long backoff = backoffInitialSeconds;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            MDC.put(Constants.MDC_AGENT_NAME, agentName);
            log.debug("[correlationId={}][agent={}] Attempt {}/{}",
                    correlationId, agentName, attempt, maxRetries);
            try {
                final T result = action.get();
                log.info("[correlationId={}][agent={}] Succeeded on attempt {}",
                        correlationId, agentName, attempt);
                return result;
            } catch (final Exception ex) {
                lastException = ex;
                log.warn("[correlationId={}][agent={}] Attempt {} failed: {}",
                        correlationId, agentName, attempt, ex.getMessage());
                if (attempt < maxRetries) {
                    sleep(backoff);
                    backoff *= backoffMultiplier;
                }
            }
        }

        throw new AgentException(ErrorCode.AGENT_FAILED,
                agentName + " failed after " + maxRetries + " attempts: " + lastException.getMessage(),
                agentName, 0, lastException);
    }

    /**
     * Sleeps for the given number of seconds for exponential back-off.
     *
     * @param seconds duration to sleep
     */
    private void sleep(final long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Retry sleep interrupted");
        }
    }

    /**
     * Builds a human-readable label describing the analysis source.
     *
     * @param request the analysis request
     * @return display label string
     */
    private String resolveSourceInfo(final AnalysisRequestDTO request) {
        return switch (request.sourceType()) {
            case GITHUB_PUBLIC, GITHUB_PRIVATE -> request.repoUrl();
            case LOCAL -> request.localPath();
        };
    }
}
