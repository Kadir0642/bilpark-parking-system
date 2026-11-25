package com.bilpark.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Records "last seen" for authenticated requests. Registered as a plain servlet filter (it runs after the
 * Spring Security chain, so the user is known). Only {@link DeviceTraceService#touch} decides whether a
 * write happens; it is called before the chain so a long-lived SSE stream counts once, when it opens.
 * Nothing here can fail the request.
 */
@Component
public class DeviceTraceFilter extends OncePerRequestFilter {

    private final DeviceTraceService service;

    public DeviceTraceFilter(DeviceTraceService service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                service.touch(auth.getName(), request.getHeader("X-App-Version"), request.getHeader("X-Device"));
            }
        } catch (RuntimeException ignored) {
            // the trace is best effort
        }
        chain.doFilter(request, response);
    }

    // SSE async redispatches and error dispatches must not count again
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
