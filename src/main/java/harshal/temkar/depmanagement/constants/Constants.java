package harshal.temkar.depmanagement.constants;

/**
 * Single source of truth for all application-wide string constants.
 * No magic strings anywhere else in the codebase - every literal lives here.
 *
 * @author Harshal Temkar
 */
public final class Constants {

    private Constants() {}

    // HTTP Headers
    public static final String CORRELATION_ID_HEADER    = "X-Correlation-ID";
    public static final String AUTHORIZATION_HEADER     = "Authorization";
    public static final String ACCEPT_HEADER            = "Accept";

    // GitHub API
    public static final String GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version";
    public static final String GITHUB_API_VERSION        = "2022-11-28";
    public static final String GITHUB_CONTENT_TYPE       = "application/vnd.github+json";
    public static final String GITHUB_RAW_CONTENT_TYPE   = "application/vnd.github.raw+json";
    public static final String POM_XML_FILENAME          = "pom.xml";
    public static final String BEARER_PREFIX             = "Bearer ";

    // Maven Central
    public static final String MAVEN_SEARCH_QUERY_TEMPLATE = "g:%s+AND+a:%s";
    public static final String MAVEN_SEARCH_ROWS           = "1";
    public static final String MAVEN_SEARCH_WT             = "json";

    // Pre-release markers
    public static final String[] PRERELEASE_MARKERS = {
        "alpha", "beta", "rc", "snapshot", "m1", "m2", "m3", "preview", "ea"
    };

    // Caffeine Cache
    public static final String CACHE_VERSION_CHECK = "versionCheckCache";

    // MDC keys
    public static final String MDC_CORRELATION_ID  = "correlationId";
    public static final String MDC_AGENT_NAME      = "agentName";
    public static final String MDC_ITERATION_PASS  = "iterationPass";

    // Agent names
    public static final String AGENT_ORCHESTRATOR  = "OrchestratorAgent";
    public static final String AGENT_SOURCE_READER = "SourceReaderAgent";
    public static final String AGENT_PARSER        = "DependencyParserAgent";
    public static final String AGENT_VERSION       = "VersionCheckerAgent";
    public static final String AGENT_ANALYSIS      = "AnalysisAgent";
    public static final String AGENT_REPORT        = "ReportBuilderAgent";

    // Iteration / confidence
    public static final String CONFIDENCE_COMPLETE = "COMPLETE";
    public static final String CONFIDENCE_HIGH     = "HIGH";
    public static final String JSON_REPAIR_SUFFIX  = "Fix the JSON and return only valid JSON. No explanation.";

    // Report chart keys
    public static final String REPORT_CHART_SEVERITY = "severityChart";
    public static final String REPORT_CHART_STATUS   = "statusChart";
    public static final String REPORT_CHART_UPDATES  = "updatesChart";

    // Dependency resolution sources
    public static final String SOURCE_DEPENDENCIES    = "dependencies";
    public static final String SOURCE_DEPENDENCY_MGMT = "dependencyManagement";
    public static final String SOURCE_PROFILE         = "profile";
    public static final String SOURCE_PLUGIN          = "plugin";
}
