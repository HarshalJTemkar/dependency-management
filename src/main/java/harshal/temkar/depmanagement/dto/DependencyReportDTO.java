package harshal.temkar.depmanagement.dto;

import harshal.temkar.depmanagement.domain.model.AnalysisResult;
import java.time.Instant;
import java.util.List;

/**
 * Top-level DTO representing the complete dependency analysis report.
 * <p>Passed directly to the Thymeleaf template as the model attribute.</p>
 *
 * @param correlationId     per-request UUID
 * @param generatedAt       timestamp of report generation
 * @param sourceInfo        human-readable description of the analysed source
 * @param totalDependencies total number of dependencies found
 * @param upToDate          number already on latest version
 * @param outdated          number with updates available
 * @param criticalCount     number of CRITICAL severity findings
 * @param highCount         number of HIGH severity findings
 * @param mediumCount       number of MEDIUM severity findings
 * @param lowCount          number of LOW severity findings
 * @param hasPartialResults {@code true} if any agent used fallback strategy
 * @param dependencies      ordered list of per-dependency analysis results
 * @param severityChartData ECharts data for severity pie chart
 * @param statusChartData   ECharts data for up-to-date vs outdated chart
 * @param updatesChartData  ECharts data for updates-by-severity bar chart
 * @author Harshal Temkar
 */
public record DependencyReportDTO(
    String correlationId,
    Instant generatedAt,
    String sourceInfo,
    int totalDependencies,
    int upToDate,
    int outdated,
    int criticalCount,
    int highCount,
    int mediumCount,
    int lowCount,
    boolean hasPartialResults,
    List<AnalysisResult> dependencies,
    ChartDataDTO severityChartData,
    ChartDataDTO statusChartData,
    ChartDataDTO updatesChartData
) {}
