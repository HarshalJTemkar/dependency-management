package harshal.temkar.depmanagement.parser;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.DependencyScope;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.domain.model.DependencyInfo;
import harshal.temkar.depmanagement.exception.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maven {@code pom.xml} parser implementing {@link DependencyParser}.
 *
 * <p>Extracts dependencies from:</p>
 * <ul>
 *   <li>{@code <dependencies>} (direct)</li>
 *   <li>{@code <dependencyManagement><dependencies>} (managed)</li>
 *   <li>{@code <build><plugins>} (plugin dependencies)</li>
 *   <li>{@code <profiles>} (profile-specific dependencies)</li>
 * </ul>
 *
 * <p>Also resolves {@code ${property}} placeholders defined in {@code <properties>}.</p>
 *
 * @author Harshal Temkar
 */
@Component
public class MavenPomParser implements DependencyParser {

    private static final Logger log = LoggerFactory.getLogger(MavenPomParser.class);

    @Override
    public String parserName() {
        return "MavenPomParser";
    }

    /**
     * Parses a pom.xml string into a list of dependency info records.
     *
     * @param buildFileContent raw pom.xml content
     * @return list of resolved {@link DependencyInfo} records
     * @throws ParseException if the XML is malformed
     */
    @Override
    public List<DependencyInfo> parse(final String buildFileContent) {
        log.debug("[correlationId={}][agent={}] Parsing pom.xml ({} chars)",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_PARSER, buildFileContent.length());

        final Document doc = parseXml(buildFileContent);
        final Map<String, String> properties = extractProperties(doc);

        final List<DependencyInfo> results = new ArrayList<>();

        // 1. Direct dependencies
        results.addAll(extractDependencies(doc, "//dependencies/dependency",
                false, Constants.SOURCE_DEPENDENCIES, properties));

        // 2. Managed dependencies
        results.addAll(extractManagedDependencies(doc, properties));

        // 3. Build plugins
        results.addAll(extractPlugins(doc, properties));

        // 4. Profile dependencies
        results.addAll(extractProfileDependencies(doc, properties));

        log.info("[correlationId={}][agent={}] Parsed {} dependencies from pom.xml",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_PARSER, results.size());
        return results;
    }

    /**
     * Parses the raw XML string into a DOM {@link Document}.
     *
     * @param xml raw XML content
     * @return parsed Document
     * @throws ParseException on malformed XML
     */
    private Document parseXml(final String xml) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (final Exception ex) {
            throw new ParseException(ErrorCode.POM_XML_MALFORMED,
                    "Failed to parse pom.xml XML: " + ex.getMessage(), 0, ex);
        }
    }

    /**
     * Extracts {@code <properties>} key-value pairs for placeholder resolution.
     *
     * @param doc parsed DOM document
     * @return map of property name → value
     */
    private Map<String, String> extractProperties(final Document doc) {
        final Map<String, String> props = new HashMap<>();
        final NodeList propNodes = doc.getElementsByTagName("properties");
        for (int i = 0; i < propNodes.getLength(); i++) {
            final Node propContainer = propNodes.item(i);
            final NodeList children = propContainer.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                final Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    props.put(child.getNodeName(), child.getTextContent().trim());
                }
            }
        }
        log.debug("[correlationId={}] Extracted {} properties",
                MDC.get(Constants.MDC_CORRELATION_ID), props.size());
        return props;
    }

    /**
     * Extracts direct {@code <dependencies>} blocks (not inside dependencyManagement).
     */
    private List<DependencyInfo> extractDependencies(final Document doc,
                                                     final String tagPath,
                                                     final boolean managed,
                                                     final String resolvedFrom,
                                                     final Map<String, String> properties) {
        final List<DependencyInfo> deps = new ArrayList<>();
        // Find top-level <dependencies> not inside <dependencyManagement>
        final NodeList allDeps = doc.getElementsByTagName("dependency");
        for (int i = 0; i < allDeps.getLength(); i++) {
            final Element dep = (Element) allDeps.item(i);
            // skip if inside <dependencyManagement>
            if (managed == isInsideManagedBlock(dep)) {
                parseDependencyElement(dep, managed, resolvedFrom, properties).ifPresent(deps::add);
            }
        }
        return deps;
    }

    /**
     * Extracts {@code <dependencyManagement><dependencies>} block.
     */
    private List<DependencyInfo> extractManagedDependencies(final Document doc,
                                                             final Map<String, String> properties) {
        final List<DependencyInfo> deps = new ArrayList<>();
        final NodeList allDeps = doc.getElementsByTagName("dependency");
        for (int i = 0; i < allDeps.getLength(); i++) {
            final Element dep = (Element) allDeps.item(i);
            if (isInsideManagedBlock(dep)) {
                parseDependencyElement(dep, true, Constants.SOURCE_DEPENDENCY_MGMT, properties)
                        .ifPresent(deps::add);
            }
        }
        return deps;
    }

    /**
     * Extracts {@code <build><plugins>} blocks as pseudo-dependencies.
     */
    private List<DependencyInfo> extractPlugins(final Document doc,
                                                final Map<String, String> properties) {
        final List<DependencyInfo> deps = new ArrayList<>();
        final NodeList plugins = doc.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            final Element plugin = (Element) plugins.item(i);
            final String groupId = childText(plugin, "groupId")
                    .orElse("org.apache.maven.plugins");
            final String artifactId = childText(plugin, "artifactId").orElse("");
            if (artifactId.isBlank()) continue;
            final String version = childText(plugin, "version")
                    .map(v -> resolve(v, properties))
                    .orElse("UNKNOWN");
            deps.add(new DependencyInfo(groupId, artifactId, version,
                    DependencyScope.RUNTIME, false, Constants.SOURCE_PLUGIN));
        }
        return deps;
    }

    /**
     * Extracts profile-specific dependency declarations.
     */
    private List<DependencyInfo> extractProfileDependencies(final Document doc,
                                                             final Map<String, String> properties) {
        final List<DependencyInfo> deps = new ArrayList<>();
        final NodeList profiles = doc.getElementsByTagName("profile");
        for (int i = 0; i < profiles.getLength(); i++) {
            final Element profile = (Element) profiles.item(i);
            final NodeList depNodes = profile.getElementsByTagName("dependency");
            for (int j = 0; j < depNodes.getLength(); j++) {
                final Element dep = (Element) depNodes.item(j);
                parseDependencyElement(dep, false, Constants.SOURCE_PROFILE, properties)
                        .ifPresent(deps::add);
            }
        }
        return deps;
    }

    /**
     * Converts a {@code <dependency>} element to a {@link DependencyInfo} record.
     *
     * @param dep          XML element for the dependency
     * @param managed      whether it is from dependencyManagement
     * @param resolvedFrom source section label
     * @param properties   property map for placeholder resolution
     * @return Optional containing the dependency, empty if groupId/artifactId missing
     */
    private Optional<DependencyInfo> parseDependencyElement(final Element dep,
                                                             final boolean managed,
                                                             final String resolvedFrom,
                                                             final Map<String, String> properties) {
        final String groupId = childText(dep, "groupId").orElse("");
        final String artifactId = childText(dep, "artifactId").orElse("");
        if (groupId.isBlank() || artifactId.isBlank()) return Optional.empty();

        final String version = childText(dep, "version")
                .map(v -> resolve(v, properties))
                .orElse("UNKNOWN");
        final String scopeStr = childText(dep, "scope").orElse("compile");
        final DependencyScope scope = parseScope(scopeStr);

        return Optional.of(new DependencyInfo(groupId, artifactId, version, scope, managed, resolvedFrom));
    }

    /**
     * Returns trimmed text content of a direct child element by tag name.
     *
     * @param parent  parent element
     * @param tagName tag to look up
     * @return Optional of non-blank text content
     */
    private Optional<String> childText(final Element parent, final String tagName) {
        final NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return Optional.empty();
        // Use only direct children (depth == 1 relative to parent)
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getParentNode() == parent) {
                final String text = nodes.item(i).getTextContent().trim();
                return text.isBlank() ? Optional.empty() : Optional.of(text);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves {@code ${property.name}} placeholders against the properties map.
     *
     * @param value      raw value possibly containing placeholders
     * @param properties resolved properties
     * @return resolved value string
     */
    private String resolve(final String value, final Map<String, String> properties) {
        if (value == null || !value.startsWith("${")) return value;
        final String key = value.replaceAll("\\$\\{(.+)}", "$1");
        return properties.getOrDefault(key, value);
    }

    /**
     * Checks whether a dependency element is nested inside a {@code <dependencyManagement>} block.
     *
     * @param dep dependency element
     * @return {@code true} if it is managed
     */
    private boolean isInsideManagedBlock(final Element dep) {
        Node parent = dep.getParentNode();
        while (parent != null) {
            if ("dependencyManagement".equals(parent.getNodeName())) return true;
            if ("profile".equals(parent.getNodeName())) return false;
            parent = parent.getParentNode();
        }
        return false;
    }

    /**
     * Maps a scope string to a {@link DependencyScope} enum constant.
     *
     * @param scope raw scope string from pom.xml
     * @return corresponding enum value, defaulting to {@code COMPILE}
     */
    private DependencyScope parseScope(final String scope) {
        return switch (scope.toLowerCase()) {
            case "test" -> DependencyScope.TEST;
            case "provided" -> DependencyScope.PROVIDED;
            case "runtime" -> DependencyScope.RUNTIME;
            case "system" -> DependencyScope.SYSTEM;
            case "import" -> DependencyScope.IMPORT;
            default -> DependencyScope.COMPILE;
        };
    }
}
