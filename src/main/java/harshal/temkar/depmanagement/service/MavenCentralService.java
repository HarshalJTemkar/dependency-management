package harshal.temkar.depmanagement.service;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.model.DependencyInfo;
import harshal.temkar.depmanagement.domain.model.VersionCheckResult;
import harshal.temkar.depmanagement.tool.MavenVersionTool;
import harshal.temkar.depmanagement.util.VersionComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service that orchestrates Maven Central version lookups for a list of dependencies.
 *
 * <p>Delegates the actual HTTP call + caching to {@link MavenVersionTool}.
 * Computes version-diff metrics using {@link VersionComparator} and assembles
 * {@link VersionCheckResult} records.</p>
 *
 * @author Harshal Temkar
 */
@Service
public class MavenCentralService {

    private static final Logger log = LoggerFactory.getLogger(MavenCentralService.class);

    private final MavenVersionTool mavenVersionTool;
    private final VersionComparator versionComparator;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param mavenVersionTool  tool for querying Maven Central (with Caffeine cache)
     * @param versionComparator utility for semantic version comparison
     */
    public MavenCentralService(final MavenVersionTool mavenVersionTool,
                                final VersionComparator versionComparator) {
        this.mavenVersionTool = mavenVersionTool;
        this.versionComparator = versionComparator;
    }

    /**
     * Fetches the latest stable version for every dependency and returns
     * {@link VersionCheckResult} records with diff metrics populated.
     *
     * @param dependencies list of parsed dependencies from pom.xml
     * @return list of version-check results (same size as input)
     */
    public List<VersionCheckResult> checkVersions(final List<DependencyInfo> dependencies) {
        log.info("[correlationId={}][agent={}] Checking {} dependencies against Maven Central",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION, dependencies.size());
        return dependencies.stream()
                .map(this::checkOne)
                .toList();
    }

    /**
     * Resolves latest version and computes diff metrics for one dependency.
     *
     * @param dep the dependency to check
     * @return populated {@link VersionCheckResult}
     */
    private VersionCheckResult checkOne(final DependencyInfo dep) {
        final String latest = mavenVersionTool.getLatestStableVersion(dep.groupId(), dep.artifactId());
        final boolean updateAvailable = !"UNKNOWN".equals(latest)
                && versionComparator.compare(dep.currentVersion(), latest) < 0;
        final int major = updateAvailable ? versionComparator.majorDiff(dep.currentVersion(), latest) : 0;
        final int minor = updateAvailable ? versionComparator.minorDiff(dep.currentVersion(), latest) : 0;
        final int patch = updateAvailable ? versionComparator.patchDiff(dep.currentVersion(), latest) : 0;
        final int versionsBehind = major + minor + patch;
        final boolean stable = !"UNKNOWN".equals(latest);

        log.debug("[correlationId={}][agent={}] {}:{} current={} latest={} updateAvailable={}",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION,
                dep.groupId(), dep.artifactId(), dep.currentVersion(), latest, updateAvailable);

        return new VersionCheckResult(dep, latest, stable, updateAvailable,
                versionsBehind, major, minor, patch);
    }
}
