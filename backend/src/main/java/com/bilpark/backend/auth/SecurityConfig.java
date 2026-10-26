package com.bilpark.backend.auth;

import org.springframework.context.annotation.Bean;
import com.bilpark.backend.common.ErrorCodes;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // Enables @PreAuthorize on controller methods
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, CustomUserDetailsService userDetailsService) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // SSE: the request was authorized when the stream opened; its async dispatches carry no token
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                        // Public endpoints
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/health",           // keep-alive / uptime check, never touches the DB
                                "/api/streets",          // streets with their zones
                                "/api/tariff/current",   // citizen page + apps show the live tariff
                                "/api/parking/debt",
                                "/api/zones",            // Officers need zones on login screen
                                "/api/zones/by-street",  // Officers filter zones by street
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll() // Citizen portal, Zones listing & Swagger
                        
                        // Online payment stays closed until stage 4 replaces it with the payment-provider flow
                        .requestMatchers("/api/parking/pay").hasRole("ADMIN")
                        .requestMatchers("/api/subscription/create").hasRole("ADMIN")

                        // Developer (maintenance) only; written in the contract annex, every call audited
                        .requestMatchers("/api/developer/**").hasRole("DEVELOPER")

                        // Admin only endpoints
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/parking/bi/**").hasRole("ADMIN")
                        .requestMatchers("/api/parking/income/**").hasRole("ADMIN")

                        // Every logged-in role: own account, license status, logout
                        .requestMatchers("/api/me", "/api/me/**", "/api/license/status", "/api/auth/logout").authenticated()

                        // Officer and Admin endpoints (the developer role has no access to operational data)
                        .anyRequest().hasAnyRole("ADMIN", "OFFICER")
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401, ErrorCodes.UNAUTHORIZED,
                                "Oturum gerekli veya süresi doldu. Lütfen tekrar giriş yapın.")) // 401, not 403
                        .accessDeniedHandler((req, res, ex) -> writeJson(res, 403, ErrorCodes.FORBIDDEN,
                                "Bu işlem için yetkiniz yok."))
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeJson(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // In a real production app, restrict these to actual frontend domains
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Device-Id", "Last-Event-ID", "Cache-Control"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
