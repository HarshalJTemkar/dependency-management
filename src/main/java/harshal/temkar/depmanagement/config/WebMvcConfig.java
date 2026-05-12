package harshal.temkar.depmanagement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Spring MVC configuration that registers a {@link LocaleChangeInterceptor}
 * for the {@code lang} request parameter.
 *
 * <p>This enables the EN / DE language toggle in {@code report.html} to work
 * by reading the {@code ?lang=en} or {@code ?lang=de} query parameter on each
 * request and updating the session locale accordingly (requires
 * {@link MessageSourceConfig} to use {@link org.springframework.web.servlet.i18n.SessionLocaleResolver}).</p>
 *
 * @author Harshal Temkar
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * Creates a {@link LocaleChangeInterceptor} that switches locale on the
     * {@code lang} request parameter.
     *
     * @return configured interceptor
     */
    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        final LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    /**
     * Registers the {@link LocaleChangeInterceptor} with Spring MVC so it
     * runs on every incoming request.
     *
     * @param registry Spring MVC interceptor registry
     */
    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
