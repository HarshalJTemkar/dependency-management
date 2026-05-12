package harshal.temkar.depmanagement.tool;
import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.exception.SourceReadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tool that reads pom.xml from the local file system.
 * <p>Supports both project root path (scans for pom.xml) and direct file path.</p>
 * @author Harshal Temkar
 */
@Component
public class LocalFileSystemTool {
    private static final Logger log = LoggerFactory.getLogger(LocalFileSystemTool.class);
    /**
     * Reads pom.xml content from the given local directory or file path.
     * @param localPath absolute path to the project root or to pom.xml directly
     * @return raw pom.xml content as string
     */
    @Tool(description = "Read pom.xml content from a local file system path. Provide an absolute directory or pom.xml file path.")
    public String readLocalPomXml(final String localPath) {
        log.debug("[correlationId={}][agent={}] Reading pom.xml from local path: {}",
            MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_SOURCE_READER, localPath);
        final Path targetPath = Paths.get(localPath);
        final Path pomPath;
        if (Files.isDirectory(targetPath)) {
            pomPath = targetPath.resolve(Constants.POM_XML_FILENAME);
        } else {
            pomPath = targetPath;
        }
        if (!Files.exists(pomPath)) {
            throw new SourceReadException(ErrorCode.POM_XML_NOT_FOUND,
 "pom.xml not found at: " + pomPath);
        }
        try {
            final String content = Files.readString(pomPath, StandardCharsets.UTF_8);
            log.info("[correlationId={}][agent={}] pom.xml read: {} bytes",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_SOURCE_READER, content.length());
            return content;
        } catch (final IOException ex) {
            throw new SourceReadException(ErrorCode.SOURCE_READ_FAILED,
 "Failed to read pom.xml: " + ex.getMessage(), ex);
        }
    }
}
