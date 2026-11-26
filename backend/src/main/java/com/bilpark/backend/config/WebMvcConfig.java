package com.bilpark.backend.config;

import com.bilpark.backend.license.ReadOnlyModeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC interceptors. There is no open-in-view (spring.jpa.open-in-view=false): a database connection is held
 * only inside a transaction / repository call, not while the response (JSON, xlsx) is written. Anything the
 * response needs from a lazy association is fetched in the query (entity graph / join fetch) or mapped inside
 * the service transaction.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ReadOnlyModeInterceptor readOnlyModeInterceptor;

    public WebMvcConfig(ReadOnlyModeInterceptor readOnlyModeInterceptor) {
        this.readOnlyModeInterceptor = readOnlyModeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // License expired (after the grace period): writes answer 423, reads stay open
        registry.addInterceptor(readOnlyModeInterceptor).addPathPatterns("/api/**");
    }
}
