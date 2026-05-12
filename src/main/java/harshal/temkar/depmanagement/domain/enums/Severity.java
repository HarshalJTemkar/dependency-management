package harshal.temkar.depmanagement.domain.enums;
/**
 * Severity level for each dependency update. CRITICAL=2+ major behind, HIGH=1 major, MEDIUM=minor, LOW=patch, UP_TO_DATE=latest.
 * @author Harshal Temkar
 */
public enum Severity {
    /** Two or more major versions behind, or CVE mentioned. */
    CRITICAL,
    /** Exactly one major version behind. */
    HIGH,
    /** Minor version behind. */
    MEDIUM,
    /** Patch version behind. */
    LOW,
    /** Already on the latest stable release. */
    UP_TO_DATE
}
