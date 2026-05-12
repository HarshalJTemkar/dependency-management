package harshal.temkar.depmanagement.dto;

import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import java.time.Instant;

/**
 * Standardised error response returned by {@code GlobalExceptionHandler}.
 * Never exposes raw stack traces to the client.
 *
 * @param correlationId  per-request identifier for cross-referencing logs
 * @param errorCode      machine-readable error classification
 * @param message        human-readable error summary
 * @param timestamp      when the error occurred
 * @param agentName      name of the agent that raised the error (may be empty)
 * @param iterationPass  pass number at which the error occurred (0 if not applicable)
 * @author Harshal Temkar
 */
public record ErrorResponseDTO(
    String correlationId,
    ErrorCode errorCode,
    String message,
    Instant timestamp,
    String agentName,
    int iterationPass
) {}
