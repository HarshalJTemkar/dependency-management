package harshal.temkar.depmanagement.agent;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.model.DependencyInfo;
import harshal.temkar.depmanagement.domain.model.VersionCheckResult;
import harshal.temkar.depmanagement.service.MavenCentralService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent that checks each dependency against Maven Central for the latest stable version.
 *
 * <p>Uses a validation loop (max {@code app.ai.iterations.version-checker.max-passes} passes)
 * to confirm stable, non-prerelease versions. Delegates HTTP + caching to
 * {@link MavenCentralService}.</p>
 *
 * @author Harshal Temkar
 */
@Component
public class VersionCheckerAgent {

    private static final Logger log = LoggerFactory.getLogger(VersionCheckerAgent.class);

    private final MavenCentralService mavenCentralService;

    @Value("${app.ai.iterations.version-checker.max-passes:2}")
    private int maxPasses;

    /**
     * Constructs the agent with the Maven Central service.
     *
     * @param mavenCentralService service for querying Maven Central Search API
     */
    public VersionCheckerAgent(final MavenCentralService mavenCentralService) {
        this.mavenCentralService = mavenCentralService;
    }

    /**
     * Checks latest stable versions for all dependencies.
     *
     * <p>Runs up to {@code maxPasses} passes, exiting early when all results
     * are stable (no pre-release or UNKNOWN versions remain).</p>
     *
     * @param dependencies list of parsed dependencies
     * @return list of version-check results with diff metrics
     */
    public List<VersionCheckResult> checkVersions(final List<DependencyInfo> dependencies) {
        MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_VERSION);
        log.info("[correlationId={}][agent={}] Starting version-check loop (maxPasses={}, deps={})",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION,
                maxPasses, dependencies.size());

        final long startNs = System.nanoTime();
        List<VersionCheckResult> results = List.of();

        try {
            for (int pass = 1; pass <= maxPasses; pass++) {
                MDC.put(Constants.MDC_ITERATION_PASS, String.valueOf(pass));
                log.debug("[correlationId={}][agent={}][pass={}] Version check pass",
                        MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION, pass);

                results = mavenCentralService.checkVersions(dependencies);

                final long unknownCount = results.stream()
                        .filter(r -> "UNKNOWN".equals(r.latestVersion())).count();

                log.info("[correlationId={}][agent={}][pass={}] Checked {} deps, {} UNKNOWN",
                        MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION,
                        pass, results.size(), unknownCount);

                if (unknownCount == 0) {
                    log.info("[correlationId={}][agent={}][pass={}] Version check COMPLETE — all resolved",
                            MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION, pass);
                    break;
                }
            }
        } finally {
            final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[correlationId={}][agent={}] COMPLETED — totalConsumed={}ms, results={}",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION, elapsedMs, results.size());
            MDC.remove(Constants.MDC_ITERATION_PASS);
        }

        return results;
    }
}
