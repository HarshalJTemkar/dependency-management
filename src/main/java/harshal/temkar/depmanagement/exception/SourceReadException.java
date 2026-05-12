package harshal.temkar.depmanagement.exception;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
/** Thrown when the SourceReaderAgent cannot access the target source. @author Harshal Temkar */
public final class SourceReadException extends DependencyManagementException {
    /**
     * @param errorCode error classification
     * @param message   human-readable message
     * @param cause     original cause
     */
    public SourceReadException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(errorCode, message, "SourceReaderAgent", 0, cause);
    }
    /** Convenience constructor without cause. */
    public SourceReadException(final ErrorCode errorCode, final String message) {
        super(errorCode, message, "SourceReaderAgent", 0, null);
    }
}
