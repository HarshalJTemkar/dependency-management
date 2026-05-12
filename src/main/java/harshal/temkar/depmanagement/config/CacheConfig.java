package harshal.temkar.depmanagement.config;
import com.github.benmanes.caffeine.cache.Caffeine;
import harshal.temkar.depmanagement.constants.Constants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;
/**
 * Caffeine in-process cache configuration.
 * <p>Cache name: {@value Constants#CACHE_VERSION_CHECK}</p>
 * <p>All tuning parameters are externalized to application.yml.</p>
 * @author Harshal Temkar
 */
@Configuration
@EnableCaching
public class CacheConfig {
    @Value("${app.cache.version-check-ttl-minutes}")
    private int ttlMinutes;
    @Value("${app.cache.max-size}")
    private int maxSize;
    /**
     * Configures a {@link CaffeineCacheManager} for Maven Central version-check results.
     * TTL: 60 min (configurable). Max size: 1000 entries (configurable).
     * @return configured CacheManager
     */
    @Bean
    public CacheManager cacheManager() {
        final CaffeineCacheManager manager = new CaffeineCacheManager(Constants.CACHE_VERSION_CHECK);
        manager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
            .maximumSize(maxSize)
            .recordStats());
        return manager;
    }
}
