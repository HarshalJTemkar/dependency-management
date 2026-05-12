package harshal.temkar.depmanagement.tool;
import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.exception.SourceReadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Tool that reads pom.xml content from a GitHub repository (public or private).
 * <p>Supports GitHub REST API v3 raw content endpoint.</p>
 * @author Harshal Temkar
 */
@Component
public class GitHubTool {
    private static final Logger log = LoggerFactory.getLogger(GitHubTool.class);
    private final WebClient githubWebClient;
    @Value("${app.github.timeout-seconds}")
    private int timeoutSeconds;
    public GitHubTool(@Qualifier("githubWebClient") final WebClient githubWebClient) {
        this.githubWebClient = githubWebClient;
    }
    /**
     * Fetches pom.xml raw content from a public GitHub repository using the REST API.
     * @param owner repository owner (user or org name)
     * @param repo  repository name
     * @param ref   branch, tag or commit SHA (default: main)
     * @return raw pom.xml content as a string
     */
    @Tool(description = "Fetch pom.xml content from a public GitHub repository. Params: owner, repo, ref (branch/tag, optional - uses default branch when blank).")
    public String fetchPublicPomXml(final String owner, final String repo, final String ref) {
        final String path = buildContentsPath(owner, repo, ref);
        log.debug("[correlationId={}][agent={}] Fetching public pom.xml: {}/{} ref={}",
            MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_SOURCE_READER, owner, repo,
            (ref == null || ref.isBlank()) ? "<default>" : ref);
        return githubWebClient.get().uri(path)
            .header(Constants.ACCEPT_HEADER, Constants.GITHUB_RAW_CONTENT_TYPE)
            .header(Constants.GITHUB_API_VERSION_HEADER, Constants.GITHUB_API_VERSION)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .doOnError(WebClientResponseException.class, ex -> log.error(
                "[correlationId={}] GitHub public fetch failed: HTTP {}",
                MDC.get(Constants.MDC_CORRELATION_ID), ex.getStatusCode()))
            .onErrorMap(WebClientResponseException.NotFound.class,
 ex -> new SourceReadException(ErrorCode.POM_XML_NOT_FOUND, "pom.xml not found in " + owner + "/" + repo, ex))
            .onErrorMap(WebClientResponseException.Unauthorized.class,
 ex -> new SourceReadException(ErrorCode.GITHUB_AUTH_FAILED, "GitHub auth failed", ex))
            .blockOptional()
            .orElseThrow(() -> new SourceReadException(ErrorCode.POM_XML_NOT_FOUND, "Empty response from GitHub"));
    }
    /**
     * Fetches pom.xml raw content from a private GitHub repository using a PAT token.
     * @param owner repository owner
     * @param repo  repository name
     * @param ref   branch or tag
     * @param token Personal Access Token
     * @return raw pom.xml content as a string
     */
    @Tool(description = "Fetch pom.xml content from a private GitHub repository using a PAT token.")
    public String fetchPrivatePomXml(final String owner, final String repo, final String ref, final String token) {
        final String path = buildContentsPath(owner, repo, ref);
        log.debug("[correlationId={}][agent={}] Fetching private pom.xml: {}/{} ref={}",
            MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_SOURCE_READER, owner, repo,
            (ref == null || ref.isBlank()) ? "<default>" : ref);
        return githubWebClient.get().uri(path)
            .header(Constants.AUTHORIZATION_HEADER, Constants.BEARER_PREFIX + token)
            .header(Constants.ACCEPT_HEADER, Constants.GITHUB_RAW_CONTENT_TYPE)
            .header(Constants.GITHUB_API_VERSION_HEADER, Constants.GITHUB_API_VERSION)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .onErrorMap(WebClientResponseException.NotFound.class,
 ex -> new SourceReadException(ErrorCode.POM_XML_NOT_FOUND, "pom.xml not found: " + owner + "/" + repo, ex))
            .onErrorMap(WebClientResponseException.Unauthorized.class,
 ex -> new SourceReadException(ErrorCode.GITHUB_AUTH_FAILED, "Invalid GitHub PAT token", ex))
            .onErrorMap(WebClientResponseException.TooManyRequests.class,
 ex -> new SourceReadException(ErrorCode.GITHUB_RATE_LIMITED, "GitHub API rate limit exceeded", ex))
            .blockOptional()
            .orElseThrow(() -> new SourceReadException(ErrorCode.POM_XML_NOT_FOUND, "Empty response from GitHub"));
    }

    /**
     * Builds the GitHub contents API path. When {@code ref} is null/blank,
     * the {@code ?ref=} parameter is omitted so GitHub uses the repository's
     * default branch automatically (no need to guess main vs master).
     */
    private String buildContentsPath(final String owner, final String repo, final String ref) {
        final String base = "/repos/" + owner + "/" + repo + "/contents/pom.xml";
        return (ref == null || ref.isBlank()) ? base : base + "?ref=" + ref;
    }

    /**
     * Parses a GitHub URL into owner/repo components.
     * Tolerant of trailing {@code .git}, trailing slashes, query strings,
     * fragments and {@code tree/<branch>} sub-paths.
     *
     * @param githubUrl full GitHub URL e.g. https://github.com/owner/repo
     * @return Map with keys: owner, repo
     */
    @Tool(description = "Parse a GitHub URL into owner and repo name components.")
    public Map<String, String> parseGithubUrl(final String githubUrl) {
        if (githubUrl == null || githubUrl.isBlank()) {
            throw new SourceReadException(ErrorCode.INVALID_INPUT, "GitHub URL is empty");
        }
        String cleaned = githubUrl.trim();
        // Strip query/fragment
        final int q = cleaned.indexOf('?');
        if (q > 0) cleaned = cleaned.substring(0, q);
        final int h = cleaned.indexOf('#');
        if (h > 0) cleaned = cleaned.substring(0, h);
        // Strip protocol + host
        cleaned = cleaned.replaceAll("^(https?://)?(www\\.)?github\\.com/", "");
        // Strip git@github.com: SSH form
        cleaned = cleaned.replaceAll("^git@github\\.com:", "");
        // Strip trailing .git, trailing slash
        if (cleaned.endsWith(".git")) cleaned = cleaned.substring(0, cleaned.length() - 4);
        if (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        final String[] parts = cleaned.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new SourceReadException(ErrorCode.INVALID_INPUT, "Invalid GitHub URL: " + githubUrl);
        }
        return Map.of("owner", parts[0], "repo", parts[1]);
    }
}