package harshal.temkar.depmanagement.domain.model;

import harshal.temkar.depmanagement.domain.enums.AgentStatus;
import java.time.Instant;

/**
 * Tracks idempotency state for a single analysis pipeline execution.
 * <p>Stored in a {@code ConcurrentHashMap} keyed by jobHash.</p>
 *
 * @param jobHash       SHA-256 of (sourceUrl + sourceType + truncated-minute timestamp)
 * @param status        current pipeline lifecycle status
 * @param correlationId per-request UUID
 * @param startedAt     timestamp when this job started
 * @author Harshal Temkar
 */
public record AnalysisJob(
    String jobHash,
    AgentStatus status,
    String correlationId,
    Instant startedAt
) {}
