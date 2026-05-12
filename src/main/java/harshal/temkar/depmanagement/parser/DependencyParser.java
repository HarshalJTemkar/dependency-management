package harshal.temkar.depmanagement.parser;

import harshal.temkar.depmanagement.domain.model.DependencyInfo;

import java.util.List;

/**
 * Strategy interface for parsing build-file dependencies.
 *
 * <p>MVP implementation: {@link MavenPomParser}.<br>
 * Future: GradleParser, NpmParser, PipParser, GoModParser.</p>
 *
 * @author Harshal Temkar
 */
public interface DependencyParser {

    /**
     * Parses the given build-file content and extracts all dependency declarations.
     *
     * @param buildFileContent raw content of the build file (e.g. pom.xml)
     * @return non-null, possibly empty list of parsed dependencies
     */
    List<DependencyInfo> parse(String buildFileContent);

    /**
     * Returns a human-readable name for this parser (used in logging).
     *
     * @return parser name, e.g. {@code "MavenPomParser"}
     */
    String parserName();
}
