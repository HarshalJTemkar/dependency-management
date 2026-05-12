package harshal.temkar.depmanagement.tool;
import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.util.VersionComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
/** Tool that queries Maven Central Search API for latest stable version. Cached via Caffeine for 60 min. @author Harshal Temkar */
@Component
public class MavenVersionTool {
    private static final Logger log = LoggerFactory.getLogger(MavenVersionTool.class);
    private final WebClient mavenWebClient;
    private final VersionComparator versionComparator;
    @Value("${app.maven.timeout-seconds}")
    private int timeoutSeconds;
    public MavenVersionTool(@Qualifier("mavenWebClient") final WebClient mavenWebClient, final VersionComparator versionComparator) {
        this.mavenWebClient = mavenWebClient;
        this.versionComparator = versionComparator;
    }
    /**
     * Fetches latest stable version of a Maven artifact from Maven Central.
     * Results cached by groupId:artifactId key.
     * @param groupId    Maven group ID
     * @param artifactId Maven artifact ID
     * @return latest stable version string, or UNKNOWN if unreachable
     */
    @Tool(description = "Look up the latest stable version of a Maven artifact on Maven Central.")
    @Cacheable(value = Constants.CACHE_VERSION_CHECK, key = "#groupId + ':' + #artifactId")
    public String getLatestStableVersion(final String groupId, final String artifactId) {
        log.debug("[correlationId={}][agent={}] Maven Central lookup: {}:{}", MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION, groupId, artifactId);
        try {
            final String query = String.format(Constants.MAVEN_SEARCH_QUERY_TEMPLATE, groupId, artifactId);
            @SuppressWarnings("unchecked")
            final Map<String, Object> response = mavenWebClient.get()
                .uri(uriBuilder -> uriBuilder.queryParam("q", query).queryParam("rows", Constants.MAVEN_SEARCH_ROWS).queryParam("wt", Constants.MAVEN_SEARCH_WT).build())
                .retrieve().bodyToMono(Map.class).timeout(Duration.ofSeconds(timeoutSeconds)).block();
            return extractLatestVersion(response).orElse("UNKNOWN");
        } catch (final Exception ex) {
            log.warn("[correlationId={}][agent={}] Maven Central unreachable for {}:{}: {}", MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_VERSION, groupId, artifactId, ex.getMessage());
            return "UNKNOWN";
        }
    }
    @SuppressWarnings("unchecked")
    private Optional<String> extractLatestVersion(final Map<String, Object> response) {
        return Optional.ofNullable(response)
            .map(r -> (Map<String, Object>) r.get("response"))
            .map(r -> (List<Map<String, Object>>) r.get("docs"))
            .filter(docs -> !docs.isEmpty())
            .map(docs -> docs.get(0))
            .map(doc -> (String) doc.get("latestVersion"))
            .filter(v -> !isPreRelease(v));
    }
    private boolean isPreRelease(final String version) {
        final String lower = version.toLowerCase();
        return Arrays.stream(Constants.PRERELEASE_MARKERS).anyMatch(lower::contains);
    }
}
