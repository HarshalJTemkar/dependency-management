package harshal.temkar.depmanagement.exception;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
/** Thrown when pom.xml cannot be parsed. @author Harshal Temkar */
public final class ParseException extends DependencyManagementException {
    /**
     * @param errorCode     error classification
     * @param message       human-readable message
     * @param iterationPass reflection pass number at failure
     * @param cause         original cause
     */
    public ParseException(final ErrorCode errorCode, final String message,
            final int iterationPass, final Throwable cause) {
        super(errorCode, message, "DependencyParserAgent", iterationPass, cause);
    }
    /** Convenience constructor without cause. */
    public ParseException(final ErrorCode errorCode, final String message) {
        super(errorCode, message, "DependencyParserAgent", 0, null);
    }
}
