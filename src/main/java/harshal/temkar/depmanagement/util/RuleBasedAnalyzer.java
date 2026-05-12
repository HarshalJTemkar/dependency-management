package harshal.temkar.depmanagement.util;

import harshal.temkar.depmanagement.domain.enums.Severity;
import harshal.temkar.depmanagement.domain.model.AnalysisResult;
import harshal.temkar.depmanagement.domain.model.VersionCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rule-based fallback analyzer used when all LLM providers are unavailable.
 * <p>Derives {@link Severity} purely from semantic version distance (major / minor / patch)
 * without any AI inference. Ensures pipeline always completes.</p>
 *
 * @author Harshal Temkar
 */
@Component
public class RuleBasedAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedAnalyzer.class);

    /**
     * Produces {@link AnalysisResult} list using version-diff rules only.
     *
     * @param versionCheckResults list of version-check outcomes from VersionCheckerAgent
     * @return list of analysis results — never null, never empty if input is non-empty
     */
    public List<AnalysisResult> analyze(final List<VersionCheckResult> versionCheckResults) {
        log.warn("[correlationId={}][agent=AnalysisAgent] LLM unavailable — using RuleBasedAnalyzer fallback",
                MDC.get("correlationId"));
        return versionCheckResults.stream()
                .map(this::analyzeOne)
                .toList();
    }

    /**
     * Derives severity and generic notes for a single dependency.
     *
     * @param vcr version-check result for one dependency
     * @return {@link AnalysisResult} with rule-derived severity
     */
    public AnalysisResult analyzeOne(final VersionCheckResult vcr) {
        final Severity severity = computeSeverity(vcr);
        final String breaking = buildBreakingNote(vcr, severity);
        final String migration = buildMigrationNote(vcr, severity);
        log.debug("[correlationId={}] Rule-based severity for {}:{} → {}",
                MDC.get("correlationId"),
                vcr.dependency().groupId(), vcr.dependency().artifactId(), severity);
        return new AnalysisResult(vcr, severity, breaking, migration, "", 0, false);
    }

    /**
     * Maps version-diff metrics to a {@link Severity} level.
     *
     * @param vcr version-check result
     * @return computed severity
     */
    private Severity computeSeverity(final VersionCheckResult vcr) {
        if (!vcr.updateAvailable()) return Severity.UP_TO_DATE;
        return switch (vcr.majorBehind()) {
            case 0 -> vcr.minorBehind() > 0 ? Severity.MEDIUM : Severity.LOW;
            case 1 -> Severity.HIGH;
            default -> Severity.CRITICAL;
        };
    }

    private String buildBreakingNote(final VersionCheckResult vcr, final Severity severity) {
        return switch (severity) {
            case CRITICAL -> "Dependency is " + vcr.majorBehind() +
                    " major version(s) behind. High probability of breaking API changes.";
            case HIGH -> "Dependency is 1 major version behind. Review release notes for breaking changes.";
            case MEDIUM -> "Minor version update. Usually backward compatible; verify deprecations.";
            case LOW -> "Patch update. Bug fixes only — generally safe to upgrade.";
            case UP_TO_DATE -> "No update required.";
        };
    }

    private String buildMigrationNote(final VersionCheckResult vcr, final Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH ->
                "Check official migration guide for " + vcr.dependency().groupId() +
                ":" + vcr.dependency().artifactId() + " " +
                vcr.dependency().currentVersion() + " → " + vcr.latestVersion();
            case MEDIUM -> "Run tests after upgrading to " + vcr.latestVersion() + ".";
            case LOW -> "Safe to update to " + vcr.latestVersion() + ".";
            case UP_TO_DATE -> "";
        };
    }
}