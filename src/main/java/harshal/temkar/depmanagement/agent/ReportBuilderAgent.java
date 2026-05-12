package harshal.temkar.depmanagement.agent;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.Severity;
import harshal.temkar.depmanagement.domain.model.AnalysisResult;
import harshal.temkar.depmanagement.dto.ChartDataDTO;
import harshal.temkar.depmanagement.dto.DependencyReportDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Agent responsible for assembling the final {@link DependencyReportDTO}.
 *
 * <p>Uses a self-validation loop (max {@code app.ai.iterations.report-builder.max-passes})
 * to confirm count consistency and ECharts data integrity before returning the report.</p>
 *
 * @author Harshal Temkar
 */
@Component
public class ReportBuilderAgent {

    private static final Logger log = LoggerFactory.getLogger(ReportBuilderAgent.class);

    @Value("${app.ai.iterations.report-builder.max-passes:2}")
    private int maxPasses;

    /**
     * Builds and self-validates the full report DTO.
     *
     * @param correlationId   per-request tracing UUID
     * @param sourceInfo      human-readable source description
     * @param results         analysis results from AnalysisAgent
     * @param hasPartialResults {@code true} if any fallback was used
     * @return fully populated, validated {@link DependencyReportDTO}
     */
    public DependencyReportDTO buildReport(final String correlationId,
                                            final String sourceInfo,
                                            final List<AnalysisResult> results,
                                            final boolean hasPartialResults) {
        MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_REPORT);
        log.info("[correlationId={}][agent={}] Building report (maxPasses={}, deps={})",
                correlationId, Constants.AGENT_REPORT, maxPasses, results.size());

        final long startNs = System.nanoTime();
        DependencyReportDTO report = null;
        try {
            for (int pass = 1; pass <= maxPasses; pass++) {
                MDC.put(Constants.MDC_ITERATION_PASS, String.valueOf(pass));
                log.debug("[correlationId={}][agent={}][pass={}] Assembling report",
                        correlationId, Constants.AGENT_REPORT, pass);

                report = assemble(correlationId, sourceInfo, results, hasPartialResults);

                if (isConsistent(report)) {
                    log.info("[correlationId={}][agent={}][pass={}] Report COMPLETE — counts consistent",
                            correlationId, Constants.AGENT_REPORT, pass);
                    break;
                }
                log.warn("[correlationId={}][agent={}][pass={}] Report inconsistency detected, recomputing",
                        correlationId, Constants.AGENT_REPORT, pass);
            }
        } finally {
            final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[correlationId={}][agent={}] COMPLETED — totalConsumed={}ms",
                    correlationId, Constants.AGENT_REPORT, elapsedMs);
            MDC.remove(Constants.MDC_ITERATION_PASS);
        }

        return report;
    }

    /**
     * Assembles the full {@link DependencyReportDTO} from the analysis results.
     *
     * @param correlationId     per-request UUID
     * @param sourceInfo        source description
     * @param results           analysis results
     * @param hasPartialResults whether fallback was used
     * @return assembled DTO
     */
    private DependencyReportDTO assemble(final String correlationId,
                                          final String sourceInfo,
                                          final List<AnalysisResult> results,
                                          final boolean hasPartialResults) {
        // Split into found vs not-found (latestVersion == UNKNOWN means Maven Central
        // could not resolve this artifact — typically private/internal deps).
        final List<AnalysisResult> notFound = results.stream()
                .filter(r -> r.versionCheckResult() != null
                        && "UNKNOWN".equalsIgnoreCase(r.versionCheckResult().latestVersion()))
                .toList();
        final List<AnalysisResult> found = results.stream()
                .filter(r -> !(r.versionCheckResult() != null
                        && "UNKNOWN".equalsIgnoreCase(r.versionCheckResult().latestVersion())))
                .toList();

        final int total = results.size();
        final int upToDate = (int) found.stream()
                .filter(r -> r.severity() == Severity.UP_TO_DATE).count();
        final int outdated = total - upToDate - notFound.size();
        final int critical = count(found, Severity.CRITICAL);
        final int high = count(found, Severity.HIGH);
        final int medium = count(found, Severity.MEDIUM);
        final int low = count(found, Severity.LOW);

        return new DependencyReportDTO(
                correlationId,
                Instant.now(),
                sourceInfo,
                total,
                upToDate,
                outdated,
                critical,
                high,
                medium,
                low,
                hasPartialResults || !notFound.isEmpty(),
                found,
                notFound,
                severityChart(critical, high, medium, low),
                statusChart(upToDate, outdated),
                updatesChart(critical, high, medium, low)
        );
    }

    /**
     * Validates that all counts add up correctly.
     *
     * @param report assembled report to check
     * @return {@code true} if consistent
     */
    private boolean isConsistent(final DependencyReportDTO report) {
        final int sumSeverity = report.criticalCount() + report.highCount()
                + report.mediumCount() + report.lowCount() + report.upToDate()
                + (report.notFoundDependencies() == null ? 0 : report.notFoundDependencies().size());
        final boolean countsOk = sumSeverity == report.totalDependencies();
        final boolean chartOk = !report.severityChartData().isEmpty();
        return countsOk && chartOk;
    }

    private int count(final List<AnalysisResult> results, final Severity severity) {
        return (int) results.stream().filter(r -> r.severity() == severity).count();
    }

    private ChartDataDTO severityChart(final int critical, final int high,
                                        final int medium, final int low) {
        return new ChartDataDTO(Constants.REPORT_CHART_SEVERITY,
                List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"),
                List.of(critical, high, medium, low),
                "Severity Distribution");
    }

    private ChartDataDTO statusChart(final int upToDate, final int outdated) {
        return new ChartDataDTO(Constants.REPORT_CHART_STATUS,
                List.of("Up to Date", "Outdated"),
                List.of(upToDate, outdated),
                "Status");
    }

    private ChartDataDTO updatesChart(final int critical, final int high,
                                       final int medium, final int low) {
        return new ChartDataDTO(Constants.REPORT_CHART_UPDATES,
                List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"),
                List.of(critical, high, medium, low),
                "Updates by Severity");
    }
}
