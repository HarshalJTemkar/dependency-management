package harshal.temkar.depmanagement.exception;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
/**
 * Sealed base exception for all application errors.
 * <p>All subclasses must carry an {@link ErrorCode} for structured error reporting.</p>
 * @author Harshal Temkar
 */
public sealed class DependencyManagementException extends RuntimeException
    permits AgentException, SourceReadException, ParseException, VersionCheckException {
    private final ErrorCode errorCode;
    private final String agentName;
    private final int iterationPass;
    /**
     * Constructs a new exception with error code, message, agent context, and iteration pass.
     * @param errorCode     machine-readable error classification
     * @param message       human-readable description
     * @param agentName     name of the agent that raised this exception
     * @param iterationPass pass number at failure (0 if not in a loop)
     * @param cause         original cause, may be null
     */
    protected DependencyManagementException(final ErrorCode errorCode, final String message,
            final String agentName, final int iterationPass, final Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.agentName = agentName;
        this.iterationPass = iterationPass;
    }
    /** @return machine-readable error code */
    public ErrorCode getErrorCode() { return errorCode; }
    /** @return name of the agent that raised this exception */
    public String getAgentName() { return agentName; }
    /** @return iteration pass number at point of failure */
    public int getIterationPass() { return iterationPass; }
}
