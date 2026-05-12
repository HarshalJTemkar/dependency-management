package harshal.temkar.depmanagement.exception;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
/** Thrown when a Spring AI agent fails during execution. @author Harshal Temkar */
public final class AgentException extends DependencyManagementException {
    /**
     * @param errorCode     error classification
     * @param message       human-readable message
     * @param agentName     name of the failing agent
     * @param iterationPass pass number at point of failure
     * @param cause         original cause
     */
    public AgentException(final ErrorCode errorCode, final String message,
            final String agentName, final int iterationPass, final Throwable cause) {
        super(errorCode, message, agentName, iterationPass, cause);
    }
    /** Convenience constructor without cause. */
    public AgentException(final ErrorCode errorCode, final String message,
            final String agentName, final int iterationPass) {
        super(errorCode, message, agentName, iterationPass, null);
    }
}
