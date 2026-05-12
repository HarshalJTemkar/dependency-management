package harshal.temkar.depmanagement.agent;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.domain.enums.SourceType;
import harshal.temkar.depmanagement.exception.SourceReadException;
import harshal.temkar.depmanagement.tool.GitHubTool;
import harshal.temkar.depmanagement.tool.LocalFileSystemTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent responsible for acquiring the raw pom.xml content.
 *
 * <p>Supports three source types:</p>
 * <ul>
 *   <li>GITHUB_PUBLIC  — GitHub REST API v3, no auth</li>
 *   <li>GITHUB_PRIVATE — GitHub REST API v3, PAT token</li>
 *   <li>LOCAL          — Java NIO file read</li>
 * </ul>
 *
 * @author Harshal Temkar
 */
@Component
public class SourceReaderAgent {

    private static final Logger log = LoggerFactory.getLogger(SourceReaderAgent.class);

    private final GitHubTool gitHubTool;
    private final LocalFileSystemTool localFileSystemTool;

    /**
     * Constructs the agent with its source-reading tools.
     *
     * @param gitHubTool          tool for GitHub API access
     * @param localFileSystemTool tool for local FS access
     */
    public SourceReaderAgent(final GitHubTool gitHubTool,
                              final LocalFileSystemTool localFileSystemTool) {
        this.gitHubTool = gitHubTool;
        this.localFileSystemTool = localFileSystemTool;
    }

    /**
     * Reads the pom.xml content from the configured source.
     *
     * @param sourceType   source type enum
     * @param repoUrl      GitHub repository URL (null for LOCAL)
     * @param localPath    local file system path (null for GitHub)
     * @param githubToken  PAT token for private repos (null for public/local)
     * @return raw pom.xml content as a string
     * @throws SourceReadException if reading fails
     */
    public String readPomXml(final SourceType sourceType,
                              final String repoUrl,
                              final String localPath,
                              final String githubToken) {
        MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_SOURCE_READER);
        log.info("[correlationId={}][agent={}] Reading pom.xml — sourceType={}",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_SOURCE_READER, sourceType);

        return switch (sourceType) {
            case GITHUB_PUBLIC -> {
                final Map<String, String> parts = gitHubTool.parseGithubUrl(repoUrl);
                yield gitHubTool.fetchPublicPomXml(parts.get("owner"), parts.get("repo"), "main");
            }
            case GITHUB_PRIVATE -> {
                if (githubToken == null || githubToken.isBlank()) {
                    throw new SourceReadException(ErrorCode.GITHUB_AUTH_FAILED,
                            "GitHub PAT token required for private repositories");
                }
                final Map<String, String> parts = gitHubTool.parseGithubUrl(repoUrl);
                yield gitHubTool.fetchPrivatePomXml(parts.get("owner"), parts.get("repo"),
                        "main", githubToken);
            }
            case LOCAL -> localFileSystemTool.readLocalPomXml(localPath);
        };
    }
}
