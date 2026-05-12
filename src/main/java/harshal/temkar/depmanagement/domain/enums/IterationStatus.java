package harshal.temkar.depmanagement.domain.enums;

/** Status of an individual iteration pass within an agent loop. @author Harshal Temkar */
public enum IterationStatus {
    /** Pass has not started yet. */
    NOT_STARTED,
    /** Pass is currently executing. */
    IN_PROGRESS,
    /** Pass completed and exit condition satisfied. */
    COMPLETED_SUCCESS,
    /** Pass completed but max iterations cap was hit before exit condition. */
    COMPLETED_MAX_REACHED,
    /** Pass failed due to LLM or parsing error. */
    FAILED
}
