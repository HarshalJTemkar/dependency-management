package harshal.temkar.depmanagement.exception;
import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.dto.ErrorResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;
import java.util.Optional;

/** Centralised exception handler. Never exposes raw stack traces. All responses use ErrorResponseDTO. @author Harshal Temkar */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /** Handles sealed domain exceptions. @param ex exception @return structured error response */
    @ExceptionHandler(DependencyManagementException.class)
    public ResponseEntity<ErrorResponseDTO> handleDomainException(final DependencyManagementException ex) {
        log.error("[correlationId={}][agent={}][pass={}] Domain exception: {}", correlationId(), ex.getAgentName(), ex.getIterationPass(), ex.getMessage(), ex);
        return ResponseEntity.status(resolveStatus(ex.getErrorCode())).body(buildResponse(ex.getErrorCode(), ex.getMessage(), ex.getAgentName(), ex.getIterationPass()));
    }
    /** Handles Bean Validation failures. @param ex validation exception @return 400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> handleValidation(final MethodArgumentNotValidException ex) {
        final String msg = ex.getBindingResult().getFieldErrors().stream().map(fe -> fe.getField() + ": " + fe.getDefaultMessage()).reduce((a,b)->a+"; "+b).orElse(ex.getMessage());
        log.warn("[correlationId={}] Validation failure: {}", correlationId(), msg);
        return ResponseEntity.badRequest().body(buildResponse(ErrorCode.INVALID_INPUT, msg, "", 0));
    }
    /** Catch-all for unexpected exceptions. @param ex throwable @return 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleGeneric(final Exception ex) {
        log.error("[correlationId={}] Unexpected error: {}", correlationId(), ex.getMessage(), ex);
        return ResponseEntity.internalServerError().body(buildResponse(ErrorCode.AGENT_FAILED, "An unexpected error occurred.", "", 0));
    }
    private ErrorResponseDTO buildResponse(final ErrorCode code, final String message, final String agentName, final int pass) {
        return new ErrorResponseDTO(correlationId(), code, message, Instant.now(), agentName, pass);
    }
    private String correlationId() {
        return Optional.ofNullable(MDC.get(Constants.MDC_CORRELATION_ID)).orElse("unknown");
    }
    private HttpStatus resolveStatus(final ErrorCode code) {
        return switch (code) {
            case INVALID_INPUT, POM_XML_MALFORMED -> HttpStatus.BAD_REQUEST;
            case GITHUB_AUTH_FAILED -> HttpStatus.UNAUTHORIZED;
            case SOURCE_NOT_FOUND, GITHUB_REPO_NOT_FOUND, POM_XML_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case GITHUB_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
