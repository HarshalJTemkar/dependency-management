package harshal.temkar.depmanagement.dto;

import harshal.temkar.depmanagement.domain.enums.SourceType;
import jakarta.validation.constraints.NotNull;

/**
 * Inbound DTO for triggering a dependency analysis.
 *
 * @param sourceType    type of source (GitHub public/private, local)
 * @param repoUrl       GitHub repository URL or {@code null} for local
 * @param localPath     absolute local file system path or {@code null} for GitHub
 * @param githubToken   Personal Access Token for private repos, {@code null} otherwise
 * @param language      preferred report language - "en" or "de" (default: "en")
 * @param correlationId client-supplied correlation ID; auto-generated if blank
 * @author Harshal Temkar
 */
public record AnalysisRequestDTO(
    @NotNull(message = "sourceType must not be null")
    SourceType sourceType,
    String repoUrl,
    String localPath,
    String githubToken,
    String language,
    String correlationId
) {}
