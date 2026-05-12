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
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

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
        log.info("[correlationId={}][agent={}] Checking {} dependencies against Maven Central (parallel)",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION, dependencies.size());
        if (dependencies.isEmpty()) {
            return List.of();
        }
        // Capture MDC from the calling thread so worker threads can restore it.
        final Map<String, String> callerMdc = MDC.getCopyOfContextMap();

        // Run lookups in parallel for big speedup (network-bound, cached).
        // Bounded thread pool prevents overwhelming Maven Central.
        final int parallelism = Math.min(16, Math.max(4, dependencies.size()));
        final ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            return pool.submit(() ->
                    dependencies.parallelStream()
                            .map(dep -> checkOne(dep, callerMdc))
                            .collect(Collectors.toList())
            ).join();
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Resolves latest version and computes diff metrics for one dependency.
     * Restores the caller's MDC context on the worker thread so all log
     * statements include the correct correlationId and agent name.
     *
     * @param dep        the dependency to check
     * @param callerMdc  MDC map captured from the calling thread
     * @return populated {@link VersionCheckResult}
     */
    private VersionCheckResult checkOne(final DependencyInfo dep, final Map<String, String> callerMdc) {
        // Propagate caller MDC to this worker thread.
        if (callerMdc != null) {
            MDC.setContextMap(callerMdc);
        }
        try {
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
        } finally {
            MDC.clear();
        }
    }
}
