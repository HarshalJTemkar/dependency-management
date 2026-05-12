package harshal.temkar.depmanagement.filter;
import harshal.temkar.depmanagement.constants.Constants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Servlet filter that ensures every inbound request has a correlation ID.
 * <p>The ID is read from {@value Constants#CORRELATION_ID_HEADER}; if absent,
 * a fresh UUID is generated and a WARNING is logged.</p>
 * <p>The ID is stored in MDC for the duration of the request and echoed
 * in the response header.</p>
 * @author Harshal Temkar
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    /**
     * Populates MDC with correlationId before the request is handled;
 * clears MDC after the response is committed.
     * @param request     incoming HTTP request
     * @param response    outgoing HTTP response
     * @param filterChain remainder of the filter chain
     * @throws ServletException on servlet error
     * @throws IOException      on I/O error
     */
    @Override
    protected void doFilterInternal(@NonNull final HttpServletRequest request,
                                     @NonNull final HttpServletResponse response,
                                     @NonNull final FilterChain filterChain)
            throws ServletException, IOException {
        final String correlationId = Optional
            .ofNullable(request.getHeader(Constants.CORRELATION_ID_HEADER))
            .filter(id -> !id.isBlank())
            .orElseGet(() -> {
                final String generated = UUID.randomUUID().toString();
                log.warn("[correlationId={}] Correlation ID missing in request - auto-generated", generated);
                return generated;
            });
        MDC.put(Constants.MDC_CORRELATION_ID, correlationId);
        MDC.put(Constants.MDC_AGENT_NAME, "");
        MDC.put(Constants.MDC_ITERATION_PASS, "0");
        response.setHeader(Constants.CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(Constants.MDC_CORRELATION_ID);
            MDC.remove(Constants.MDC_AGENT_NAME);
            MDC.remove(Constants.MDC_ITERATION_PASS);
        }
    }
}
