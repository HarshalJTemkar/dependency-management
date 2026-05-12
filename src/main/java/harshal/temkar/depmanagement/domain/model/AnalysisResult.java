package harshal.temkar.depmanagement.domain.model;

import harshal.temkar.depmanagement.domain.enums.Severity;

/**
 * Immutable record holding the full LLM analysis output for one dependency.
 * <p>Produced by {@code AnalysisAgent} after its multi-pass enrichment loop.</p>
 *
 * @param versionCheckResult the version-check data this analysis is based on
 * @param severity           computed severity level
 * @param breakingChanges    human-readable summary of breaking API changes
 * @param migrationNotes     step-by-step migration guidance
 * @param releaseNotesUrl    URL to official release notes (may be empty)
 * @param analysisPass       which iteration pass produced this final result
 * @param isLlmAnalyzed      {@code false} if RuleBasedAnalyzer fallback was used
 * @author Harshal Temkar
 */
public record AnalysisResult(
    VersionCheckResult versionCheckResult,
    Severity severity,
    String breakingChanges,
    String migrationNotes,
    String releaseNotesUrl,
    int analysisPass,
    boolean isLlmAnalyzed
) {}
