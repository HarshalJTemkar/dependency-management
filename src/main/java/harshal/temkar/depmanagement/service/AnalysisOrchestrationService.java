package harshal.temkar.depmanagement.service;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.AgentStatus;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.domain.model.AnalysisJob;
import harshal.temkar.depmanagement.domain.model.AnalysisResult;
import harshal.temkar.depmanagement.domain.model.DependencyInfo;
import harshal.temkar.depmanagement.domain.model.VersionCheckResult;
import harshal.temkar.depmanagement.dto.AnalysisRequestDTO;
import harshal.temkar.depmanagement.dto.DependencyReportDTO;
import harshal.temkar.depmanagement.exception.AgentException;
import harshal.temkar.depmanagement.parser.MavenPomParser;
import harshal.temkar.depmanagement.tool.GitHubTool;
import harshal.temkar.depmanagement.tool.LocalFileSystemTool;
import harshal.temkar.depmanagement.util.CorrelationIdUtil;
import harshal.temkar.depmanagement.util.RuleBasedAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core service that orchestrates the full dependency analysis pipeline.
 *
 * <p>Handles idempotency, coordinates source reading, parsing, version checking,
 * LLM analysis and report assembly. Delegates each responsibility to the
 * appropriate collaborator.</p>
 *
 * @author Harshal Temkar
 */
@Service
public class AnalysisOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrationService.class);

    private final GitHubTool gitHubTool;
    private final LocalFileSystemTool localFileSystemTool;
    private final MavenPomParser mavenPomParser;
    private final MavenCentralService mavenCentralService;
    private final RuleBasedAnalyzer ruleBasedAnalyzer;

    /** In-memory idempotency map — jobHash → AnalysisJob. */
    private final ConcurrentHashMap<String, AnalysisJob> jobMap = new ConcurrentHashMap<>();

    /**
     * Constructs the orchestration service with all required collaborators.
     *
     * @param gitHubTool          tool for reading from GitHub
     * @param localFileSystemTool tool for reading from local FS
     * @param mavenPomParser      parser for pom.xml files
     * @param mavenCentralService service for Maven Central version lookup
     * @param ruleBasedAnalyzer   fallback analyzer when LLM is unavailable
     */
    public AnalysisOrchestrationService(final GitHubTool gitHubTool,
                                         final LocalFileSystemTool localFileSystemTool,
                                         final MavenPomParser mavenPomParser,
                                         final MavenCentralService mavenCentralService,
                                         final RuleBasedAnalyzer ruleBasedAnalyzer) {
        this.gitHubTool = gitHubTool;
        this.localFileSystemTool = localFileSystemTool;
        this.mavenPomParser = mavenPomParser;
        this.mavenCentralService = mavenCentralService;
        this.ruleBasedAnalyzer = ruleBasedAnalyzer;
    }

    /**
     * Runs the full analysis pipeline for the given request.
     *
     * @param request validated analysis request DTO
     * @return fully assembled dependency report
     */
    public DependencyReportDTO analyze(final AnalysisRequestDTO request) {
        final String correlationId = CorrelationIdUtil.resolveOrGenerate(request.correlationId());
        MDC.put(Constants.MDC_CORRELATION_ID, correlationId);
        MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_ORCHESTRATOR);

        log.info("[correlationId={}] Starting analysis pipeline — source={} type={}",
                correlationId, sourceLabel(request), request.sourceType());

        final String jobHash = computeJobHash(request);
        checkIdempotency(jobHash, correlationId);
        markRunning(jobHash, correlationId);

        try {
            // Step 1 — Read pom.xml
            MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_SOURCE_READER);
            final String pomXml = readPomXml(request);
            log.info("[correlationId={}][agent={}] pom.xml acquired ({} chars)",
                    correlationId, Constants.AGENT_SOURCE_READER, pomXml.length());

            // Step 2 — Parse dependencies
            MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_PARSER);
            final List<DependencyInfo> dependencies = mavenPomParser.parse(pomXml);
            log.info("[correlationId={}][agent={}] {} dependencies parsed",
                    correlationId, Constants.AGENT_PARSER, dependencies.size());

            // Step 3 — Version check
            MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_VERSION);
            final List<VersionCheckResult> versionResults = mavenCentralService.checkVersions(dependencies);
            log.info("[correlationId={}][agent={}] Version check complete",
                    correlationId, Constants.AGENT_VERSION);

            // Step 4 — Analysis (rule-based; LLM agents are invoked separately via OrchestratorAgent)
            MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_ANALYSIS);
            final List<AnalysisResult> analysisResults = ruleBasedAnalyzer.analyze(versionResults);
            log.info("[correlationId={}][agent={}] Analysis complete ({} results)",
                    correlationId, Constants.AGENT_ANALYSIS, analysisResults.size());

            // Step 5 — Build report
            MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_REPORT);
            final DependencyReportDTO report = buildReport(correlationId, sourceLabel(request),
                    analysisResults, true);
            log.info("[correlationId={}][agent={}] Report assembled", correlationId, Constants.AGENT_REPORT);

            markCompleted(jobHash);
            return report;
        } catch (final Exception ex) {
            markFailed(jobHash);
            log.error("[correlationId={}] Pipeline failed: {}", correlationId, ex.getMessage(), ex);
            throw new AgentException(ErrorCode.AGENT_FAILED,
                    "Analysis pipeline failed: " + ex.getMessage(), Constants.AGENT_ORCHESTRATOR, 0, ex);
        } finally {
            MDC.clear();
        }
    }

    /**
     * Reads pom.xml content based on the source type declared in the request.
     *
     * @param request the analysis request
     * @return raw pom.xml content string
     */
    private String readPomXml(final AnalysisRequestDTO request) {
        return switch (request.sourceType()) {
            case GITHUB_PUBLIC -> {
                final var parts = gitHubTool.parseGithubUrl(request.repoUrl());
                yield gitHubTool.fetchPublicPomXml(parts.get("owner"), parts.get("repo"), "main");
            }
            case GITHUB_PRIVATE -> {
                final var parts = gitHubTool.parseGithubUrl(request.repoUrl());
                yield gitHubTool.fetchPrivatePomXml(parts.get("owner"), parts.get("repo"),
                        "main", request.githubToken());
            }
            case LOCAL -> localFileSystemTool.readLocalPomXml(request.localPath());
        };
    }

    /**
     * Assembles a {@link DependencyReportDTO} from the analysis results.
     *
     * @param correlationId   per-request tracing ID
     * @param sourceInfo      human-readable source description
     * @param results         per-dependency analysis results
     * @param hasPartialResults whether any fallback was used
     * @return fully populated report DTO
     */
    private DependencyReportDTO buildReport(final String correlationId,
                                             final String sourceInfo,
                                             final List<AnalysisResult> results,
                                             final boolean hasPartialResults) {
        // Partition results: dependencies not resolvable on Maven Central
        // are flagged via a sentinel "UNKNOWN" latestVersion from MavenCentralService.
        final List<AnalysisResult> notFoundDependencies = results.stream()
                .filter(r -> r.versionCheckResult() != null
                        && "UNKNOWN".equals(r.versionCheckResult().latestVersion()))
                .toList();
        final List<AnalysisResult> foundDependencies = results.stream()
                .filter(r -> r.versionCheckResult() == null
                        || !"UNKNOWN".equals(r.versionCheckResult().latestVersion()))
                .toList();

        final int total = foundDependencies.size();
        final long upToDate = foundDependencies.stream()
                .filter(r -> !r.versionCheckResult().updateAvailable()).count();
        final long outdated = total - upToDate;
        final long critical = count(foundDependencies, harshal.temkar.depmanagement.domain.enums.Severity.CRITICAL);
        final long high = count(foundDependencies, harshal.temkar.depmanagement.domain.enums.Severity.HIGH);
        final long medium = count(foundDependencies, harshal.temkar.depmanagement.domain.enums.Severity.MEDIUM);
        final long low = count(foundDependencies, harshal.temkar.depmanagement.domain.enums.Severity.LOW);

        final harshal.temkar.depmanagement.dto.ChartDataDTO severityChart =
                buildSeverityChart(critical, high, medium, low);
        final harshal.temkar.depmanagement.dto.ChartDataDTO statusChart =
                buildStatusChart(upToDate, outdated);
        final harshal.temkar.depmanagement.dto.ChartDataDTO updatesChart =
                buildUpdatesChart(critical, high, medium, low);

        return new DependencyReportDTO(
                correlationId,
                Instant.now(),
                sourceInfo,
                total,
                (int) upToDate,
                (int) outdated,
                (int) critical,
                (int) high,
                (int) medium,
                (int) low,
                hasPartialResults || !notFoundDependencies.isEmpty(),
                foundDependencies,
                notFoundDependencies,
                severityChart,
                statusChart,
                updatesChart
        );
    }

    private long count(final List<AnalysisResult> results,
                       final harshal.temkar.depmanagement.domain.enums.Severity severity) {
        return results.stream().filter(r -> r.severity() == severity).count();
    }

    private harshal.temkar.depmanagement.dto.ChartDataDTO buildSeverityChart(
            final long critical, final long high, final long medium, final long low) {
        return new harshal.temkar.depmanagement.dto.ChartDataDTO(
                Constants.REPORT_CHART_SEVERITY,
                List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"),
                List.of((int) critical, (int) high, (int) medium, (int) low),
                "Severity Distribution");
    }

    private harshal.temkar.depmanagement.dto.ChartDataDTO buildStatusChart(
            final long upToDate, final long outdated) {
        return new harshal.temkar.depmanagement.dto.ChartDataDTO(
                Constants.REPORT_CHART_STATUS,
                List.of("Up to Date", "Outdated"),
                List.of((int) upToDate, (int) outdated),
                "Status");
    }

    private harshal.temkar.depmanagement.dto.ChartDataDTO buildUpdatesChart(
            final long critical, final long high, final long medium, final long low) {
        return new harshal.temkar.depmanagement.dto.ChartDataDTO(
                Constants.REPORT_CHART_UPDATES,
                List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"),
                List.of((int) critical, (int) high, (int) medium, (int) low),
                "Updates by Severity");
    }

    /**
     * Computes a SHA-256 job hash for idempotency key generation.
     *
     * @param request the analysis request
     * @return hex-encoded SHA-256 hash string
     */
    private String computeJobHash(final AnalysisRequestDTO request) {
        try {
            final String minuteStamp = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString();
            final String raw = request.sourceType() + "|"
                    + (request.repoUrl() != null ? request.repoUrl() : request.localPath())
                    + "|" + minuteStamp;
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (final Exception ex) {
            log.warn("Could not compute job hash, skipping idempotency check: {}", ex.getMessage());
            return java.util.UUID.randomUUID().toString();
        }
    }

    /**
     * Checks idempotency map — throws 409-equivalent exception if job is in progress.
     *
     * @param jobHash       computed hash for this request
     * @param correlationId correlation ID for logging
     */
    private void checkIdempotency(final String jobHash, final String correlationId) {
        final AnalysisJob existing = jobMap.get(jobHash);
        if (existing != null && existing.status() == AgentStatus.RUNNING) {
            log.warn("[correlationId={}] Duplicate job detected — jobHash={} existingCorrelationId={}",
                    correlationId, jobHash, existing.correlationId());
            throw new AgentException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Analysis already in progress. CorrelationId: " + existing.correlationId(),
                    Constants.AGENT_ORCHESTRATOR, 0, null);
        }
    }

    private void markRunning(final String jobHash, final String correlationId) {
        jobMap.put(jobHash, new AnalysisJob(jobHash, AgentStatus.RUNNING, correlationId, Instant.now()));
    }

    private void markCompleted(final String jobHash) {
        jobMap.remove(jobHash);
    }

    private void markFailed(final String jobHash) {
        jobMap.remove(jobHash);
    }

    /**
     * Returns a human-readable source description for the report.
     *
     * @param request the analysis request
     * @return display label
     */
    private String sourceLabel(final AnalysisRequestDTO request) {
        return switch (request.sourceType()) {
            case GITHUB_PUBLIC, GITHUB_PRIVATE -> request.repoUrl();
            case LOCAL -> request.localPath();
        };
    }
}
