package harshal.temkar.depmanagement.domain.model;

/**
 * Immutable record holding Maven Central version-check data for one dependency.
 * <p>Produced by {@code VersionCheckerAgent} after its validation loop.</p>
 *
 * @param dependency       the parsed dependency this result belongs to
 * @param latestVersion    latest stable version found on Maven Central
 * @param isStable         {@code true} if latestVersion is a stable release
 * @param updateAvailable  {@code true} if currentVersion != latestVersion
 * @param versionsBehind   total count of released versions skipped
 * @param majorBehind      number of major version increments behind
 * @param minorBehind      number of minor version increments behind
 * @param patchBehind      number of patch version increments behind
 * @author Harshal Temkar
 */
public record VersionCheckResult(
    DependencyInfo dependency,
    String latestVersion,
    boolean isStable,
    boolean updateAvailable,
    int versionsBehind,
    int majorBehind,
    int minorBehind,
    int patchBehind
) {}
