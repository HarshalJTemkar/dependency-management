package harshal.temkar.depmanagement.domain.model;

import harshal.temkar.depmanagement.domain.enums.IterationStatus;

/**
 * Immutable record tracking the state of one iteration pass inside an agent loop.
 *
 * @param agentName     name of the owning agent (from Constants)
 * @param passNumber    1-based pass counter
 * @param maxPasses     maximum allowed passes
 * @param status        current iteration status
 * @param correlationId per-request correlation identifier for MDC logging
 * @author Harshal Temkar
 */
public record IterationContext(
    String agentName,
    int passNumber,
    int maxPasses,
    IterationStatus status,
    String correlationId
) {
    /**
     * Returns {@code true} when further iteration passes are allowed.
     *
     * @return {@code true} if passNumber is less than maxPasses
     */
    public boolean canContinue() {
        return passNumber < maxPasses;
    }
}
