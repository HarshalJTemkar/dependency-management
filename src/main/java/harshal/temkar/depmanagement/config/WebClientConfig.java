package harshal.temkar.depmanagement.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient configuration for GitHub and Maven Central API calls.
 * <p>All timeouts are fully configurable via application.yml.</p>
 *
 * @author Harshal Temkar
 */
@Configuration
public class WebClientConfig {

    @Value("${app.github.api-base-url}")
    private String githubBaseUrl;

    @Value("${app.github.timeout-seconds}")
    private int githubTimeoutSeconds;

    @Value("${app.maven.central-search-url}")
    private String mavenBaseUrl;

    @Value("${app.maven.timeout-seconds}")
    private int mavenTimeoutSeconds;

    /**
     * WebClient pre-configured for GitHub REST API v3 calls.
     *
     * @return GitHub WebClient with configured timeout
     */
    @Bean
    public WebClient githubWebClient() {
        return buildWebClient(githubBaseUrl, githubTimeoutSeconds);
    }

    /**
     * WebClient pre-configured for Maven Central Search API calls.
     *
     * @return Maven Central WebClient with configured timeout
     */
    @Bean
    public WebClient mavenWebClient() {
        return buildWebClient(mavenBaseUrl, mavenTimeoutSeconds);
    }

    /**
     * Builds a WebClient with Netty reactor connector and full timeout configuration.
     *
     * @param baseUrl        root URL for this client
     * @param timeoutSeconds connection + read + write timeout in seconds
     * @return configured WebClient instance
     */
    private WebClient buildWebClient(final String baseUrl, final int timeoutSeconds) {
        final HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutSeconds * 1000)
            .responseTimeout(Duration.ofSeconds(timeoutSeconds))
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));
        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
