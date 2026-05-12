package harshal.temkar.depmanagement.domain.model;

import harshal.temkar.depmanagement.domain.enums.DependencyScope;

/**
 * Immutable record representing a single dependency extracted from pom.xml.
 * <p>Produced by {@code DependencyParserAgent} after its reflection loop.</p>
 *
 * @param groupId       Maven groupId (e.g. org.springframework.boot)
 * @param artifactId    Maven artifactId (e.g. spring-boot-starter-web)
 * @param currentVersion version string as declared in pom.xml (may contain property placeholders before resolution)
 * @param scope         Maven dependency scope
 * @param isManaged     {@code true} if declared inside {@code <dependencyManagement>}
 * @param resolvedFrom  section of pom.xml where this dependency was found
 * @author Harshal Temkar
 */
public record DependencyInfo(
    String groupId,
    String artifactId,
    String currentVersion,
    DependencyScope scope,
    boolean isManaged,
    String resolvedFrom
) {
    /**
     * Returns a human-readable coordinate string suitable for logging.
     *
     * @return {@code groupId:artifactId:currentVersion}
     */
    public String coordinate() {
        return groupId + ":" + artifactId + ":" + currentVersion;
    }
}
