package com.bilpark.backend.config;

import com.bilpark.backend.license.ReadOnlyModeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open-in-view as before (spring.jpa.open-in-view=false turns off Boot's global one), except for the
 * long-lived SSE stream: there an open EntityManager would hold a pooled connection for the whole
 * stream and keep the (Neon) database awake.
 */
@Configuration
public class OpenEntityManagerInViewConfig implements WebMvcConfigurer {

    public static final String SSE_PATH = "/api/events";

    @Bean
    public OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
        return new OpenEntityManagerInViewInterceptor();
    }

    private final ReadOnlyModeInterceptor readOnlyModeInterceptor;

    public OpenEntityManagerInViewConfig(ReadOnlyModeInterceptor readOnlyModeInterceptor) {
        this.readOnlyModeInterceptor = readOnlyModeInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addWebRequestInterceptor(openEntityManagerInViewInterceptor())
                .excludePathPatterns(SSE_PATH, SSE_PATH + "/**");
        // License expired (after the grace period): writes answer 423, reads stay open
        registry.addInterceptor(readOnlyModeInterceptor).addPathPatterns("/api/**");
    }
}
