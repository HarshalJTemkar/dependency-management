package harshal.temkar.depmanagement.agent;

import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.ErrorCode;
import harshal.temkar.depmanagement.domain.model.DependencyInfo;
import harshal.temkar.depmanagement.exception.ParseException;
import harshal.temkar.depmanagement.parser.MavenPomParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent that parses pom.xml content into a structured list of dependencies.
 *
 * <p>Uses a reflection loop (up to {@code app.ai.iterations.parser.max-passes} passes)
 * to validate completeness of extraction. Each pass logs its iteration number via MDC.</p>
 *
 * @author Harshal Temkar
 */
@Component
public class DependencyParserAgent {

    private static final Logger log = LoggerFactory.getLogger(DependencyParserAgent.class);

    private final MavenPomParser mavenPomParser;

    @Value("${app.ai.iterations.parser.max-passes:3}")
    private int maxPasses;

    /**
     * Constructs the agent with the Maven pom.xml parser.
     *
     * @param mavenPomParser parser implementation for pom.xml files
     */
    public DependencyParserAgent(final MavenPomParser mavenPomParser) {
        this.mavenPomParser = mavenPomParser;
    }

    /**
     * Parses the pom.xml content using a reflection loop for completeness.
     *
     * <p>Pass 1: initial parse. Pass 2+: verify count stability (no new deps found).
     * If count stabilises, exits early — treated as COMPLETE.</p>
     *
     * @param pomXml raw pom.xml content
     * @return complete list of parsed dependencies
     * @throws ParseException if parsing fails on all passes
     */
    public List<DependencyInfo> parse(final String pomXml) {
        MDC.put(Constants.MDC_AGENT_NAME, Constants.AGENT_PARSER);
        log.info("[correlationId={}][agent={}] Starting dependency parse loop (maxPasses={})",
                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_PARSER, maxPasses);

        final long startNs = System.nanoTime();
        List<DependencyInfo> result = new ArrayList<>();
        int previousCount = -1;

        try {
            for (int pass = 1; pass <= maxPasses; pass++) {
                MDC.put(Constants.MDC_ITERATION_PASS, String.valueOf(pass));
                log.debug("[correlationId={}][agent={}][pass={}] Reflection loop — parsing",
                        MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_PARSER, pass);

                try {
                    result = mavenPomParser.parse(pomXml);
                    log.info("[correlationId={}][agent={}][pass={}] Parsed {} dependencies",
                            MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_PARSER, pass, result.size());

                    if (result.size() == previousCount) {
                        log.info("[correlationId={}][agent={}][pass={}] Parse COMPLETE — count stable at {}",
                                MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_PARSER, pass, result.size());
                        break;
                    }
                    previousCount = result.size();

                } catch (final ParseException ex) {
                    log.error("[correlationId={}][agent={}][pass={}] Parse failed: {}",
                            MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_PARSER, pass, ex.getMessage());
                    if (pass == maxPasses) throw ex;
                }
            }
        } finally {
            final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("[correlationId={}][agent={}] COMPLETED — totalConsumed={}ms, dependencies={}",
                    MDC.get(Constants.MDC_CORRELATION_ID), Constants.AGENT_PARSER, elapsedMs, result.size());
            MDC.remove(Constants.MDC_ITERATION_PASS);
        }

        return result;
    }
}
