package harshal.temkar.depmanagement.domain.enums;

/** Maven dependency scope. @author Harshal Temkar */
public enum DependencyScope {
    /** Available at compile time and in final artifact. */
    COMPILE,
    /** Provided by runtime container - not included in artifact. */
    PROVIDED,
    /** Available only at runtime. */
    RUNTIME,
    /** Available only during test compilation and execution. */
    TEST,
    /** Included as a system-level dependency. */
    SYSTEM,
    /** Import scope - used only in dependencyManagement POM type. */
    IMPORT,
    /** Scope could not be determined from pom.xml. */
    UNKNOWN
}
