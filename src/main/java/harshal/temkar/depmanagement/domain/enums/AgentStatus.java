package harshal.temkar.depmanagement.domain.enums;

/** Lifecycle status of a Spring AI agent execution. @author Harshal Temkar */
public enum AgentStatus {
    /** Agent has not yet been invoked. */
    PENDING,
    /** Agent is currently processing. */
    RUNNING,
    /** Agent completed successfully. */
    COMPLETED,
    /** Agent failed and fallback was applied. */
    FAILED_WITH_FALLBACK,
    /** Agent failed and no fallback was available. */
    FAILED
}
