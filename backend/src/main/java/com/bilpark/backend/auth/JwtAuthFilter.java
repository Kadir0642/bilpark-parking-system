package com.bilpark.backend.auth;

import com.bilpark.backend.sync.TicketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    private final TicketService ticketService;

    public JwtAuthFilter(JwtUtil jwtUtil, CustomUserDetailsService userDetailsService, TicketService ticketService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.ticketService = ticketService;
    }

    /** Browser EventSource cannot send headers: /api/events accepts a one-time ?ticket= instead. */
    private void authenticateByTicket(HttpServletRequest request) {
        String username = ticketService.consume(request.getParameter("ticket"));
        if (username == null) return;
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (userDetails.isEnabled()) {
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        } catch (Exception e) {
            // unknown user: stay anonymous
        }
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String username;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if ("/api/events".equals(request.getRequestURI()) && request.getParameter("ticket") != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                authenticateByTicket(request);
            }
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);
        try {
            username = jwtUtil.extractUsername(jwt);
            
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);
                
                if (userDetails.isEnabled() && jwtUtil.validateToken(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Token is invalid, expired, etc. We just don't set the authentication.
            // Spring Security will handle the unauthorized access.
        }
        
        filterChain.doFilter(request, response);
    }
}
