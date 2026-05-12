package harshal.temkar.depmanagement.dto;

import harshal.temkar.depmanagement.domain.enums.AgentStatus;

/**
 * Wraps a single agent execution outcome for use by the OrchestratorAgent.
 *
 * @param agentName     name of the agent that produced this response
 * @param status        execution status after the agent finished
 * @param payload       serialised result (JSON string) or empty string on failure
 * @param errorMessage  human-readable error details, empty on success
 * @param iterationPass the last iteration pass number executed
 * @param usedFallback  {@code true} if the agent fell back to a non-LLM strategy
 * @author Harshal Temkar
 */
public record AgentResponseDTO(
    String agentName,
    AgentStatus status,
    String payload,
    String errorMessage,
    int iterationPass,
    boolean usedFallback
) {}
