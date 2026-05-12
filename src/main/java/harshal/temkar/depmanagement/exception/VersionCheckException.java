package harshal.temkar.depmanagement.exception;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
/** Thrown when Maven Central version resolution fails. @author Harshal Temkar */
public final class VersionCheckException extends DependencyManagementException {
    /**
     * @param errorCode error classification
     * @param message   human-readable message
     * @param cause     original cause
     */
    public VersionCheckException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(errorCode, message, "VersionCheckerAgent", 0, cause);
    }
    /** Convenience constructor without cause. */
    public VersionCheckException(final ErrorCode errorCode, final String message) {
        super(errorCode, message, "VersionCheckerAgent", 0, null);
    }
}
