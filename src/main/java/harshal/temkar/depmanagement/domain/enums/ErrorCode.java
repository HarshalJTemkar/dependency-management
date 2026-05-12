package harshal.temkar.depmanagement.domain.enums;

/**
 * Enumeration of all application error codes.
 * Each code maps to a specific failure scenario and is included in ErrorResponseDTO.
 * @author Harshal Temkar
 */
public enum ErrorCode {
    SOURCE_NOT_FOUND,
    SOURCE_READ_FAILED,
    GITHUB_AUTH_FAILED,
    GITHUB_RATE_LIMITED,
    GITHUB_REPO_NOT_FOUND,
    POM_XML_NOT_FOUND,
    POM_XML_MALFORMED,
    MAVEN_CENTRAL_UNREACHABLE,
    MAVEN_CENTRAL_TIMEOUT,
    LLM_UNAVAILABLE,
    LLM_JSON_PARSE_FAILED,
    LLM_MAX_ITERATIONS_REACHED,
    AGENT_TIMEOUT,
    AGENT_FAILED,
    VERSION_UNKNOWN,
    INVALID_INPUT,
    CORRELATION_ID_MISSING,
    IDEMPOTENCY_CONFLICT
}
