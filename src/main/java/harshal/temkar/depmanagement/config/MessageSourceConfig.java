package harshal.temkar.depmanagement.config;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;
import java.util.Locale;
/**
 * i18n configuration supporting English (default) and German.
 * <p>Uses {@link SessionLocaleResolver} so the {@code lang} URL query parameter
 * (set by the language toggle in the Thymeleaf UI) is applied via
 * {@link org.springframework.web.servlet.i18n.LocaleChangeInterceptor}
 * registered in {@link WebMvcConfig}.</p>
 *
 * @author Harshal Temkar
 */
@Configuration
public class MessageSourceConfig {
    /**
     * Registers a reloadable message source pointing to the i18n bundle.
     * @return configured MessageSource
     */
    @Bean
    public MessageSource messageSource() {
        final ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setCacheSeconds(3600);
        return ms;
    }
    /**
     * Uses SessionLocaleResolver so locale can be changed via the {@code lang}
     * request parameter handled by LocaleChangeInterceptor.
     * Defaults to English.
     * @return LocaleResolver
     */
    @Bean
    public LocaleResolver localeResolver() {
        final SessionLocaleResolver resolver = new SessionLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }
}
