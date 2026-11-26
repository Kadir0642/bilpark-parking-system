package com.bilpark.backend.auth;

import com.bilpark.backend.common.Overload;
import com.bilpark.backend.sync.TicketService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Bearer JWT (or the one-time SSE ticket) to an authenticated user.
 * An invalid, expired or revoked token, or an unknown/passive user, stays anonymous: Spring Security answers 401.
 * A VALID token whose user cannot be read because the database is overloaded or unreachable gets
 * 503 SERVICE_UNAVAILABLE + Retry-After instead: the client keeps the session and retries (a 401 would log the
 * officer out and the re-login storm would make the overload worse).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    private final TicketService ticketService;

    public JwtAuthFilter(JwtUtil jwtUtil, CustomUserDetailsService userDetailsService, TicketService ticketService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.ticketService = ticketService;
    }

    /** Thrown when the user lookup failed because the database could not be reached in time. */
    private static final class LookupFailed extends Exception {
        LookupFailed(Throwable cause) { super(cause); }
    }

    /** null = no such user (or an unexpected non-database error, logged). */
    private UserDetails lookup(String username) throws LookupFailed {
        try {
            return userDetailsService.loadForToken(username);
        } catch (UsernameNotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            if (Overload.isDatabaseUnavailable(e)) throw new LookupFailed(e);
            // Not a database outage (a bug): stays anonymous (401) as before, but is logged
            log.error("Kullanıcı doğrulamada beklenmeyen hata", e);
            return null;
        }
    }

    private static void authenticate(HttpServletRequest request, UserDetails userDetails) {
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    /** Browser EventSource cannot send headers: /api/events accepts a one-time ?ticket= instead. */
    private void authenticateByTicket(HttpServletRequest request) throws LookupFailed {
        String username = ticketService.consume(request.getParameter("ticket"));
        if (username == null) return;
        UserDetails userDetails = lookup(username);
        if (userDetails != null && userDetails.isEnabled()) authenticate(request, userDetails);
    }

    /** A password change or reset invalidates older tokens (JWT iat has second precision). */
    private static boolean issuedBeforePasswordChange(Date iat, UserDetails userDetails) {
        if (!(userDetails instanceof BilparkUserDetails b) || b.getPasswordChangedAt() == null) return false;
        return iat == null || iat.toInstant().isBefore(b.getPasswordChangedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                if ("/api/events".equals(request.getRequestURI()) && request.getParameter("ticket") != null
                        && SecurityContextHolder.getContext().getAuthentication() == null) {
                    authenticateByTicket(request);
                }
            } else if (SecurityContextHolder.getContext().getAuthentication() == null) {
                authenticateByToken(request, authHeader.substring(7));
            }
        } catch (LookupFailed e) {
            if (Overload.shouldLog()) {
                log.warn("Kullanıcı doğrulanamadı (veritabanı yanıt vermedi), 503: {}", rootMessage(e.getCause()));
            }
            Overload.write(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void authenticateByToken(HttpServletRequest request, String jwt) throws LookupFailed {
        Claims claims;
        try {
            claims = jwtUtil.parseClaims(jwt); // signature + expiry
        } catch (JwtException | IllegalArgumentException e) {
            return; // invalid / expired token: anonymous, Spring Security answers 401
        }
        String username = claims.getSubject();
        Date exp = claims.getExpiration();
        if (username == null || exp == null || !exp.toInstant().isAfter(Instant.now())) return;

        UserDetails userDetails = lookup(username);
        if (userDetails == null || !userDetails.isEnabled()) return;
        if (!username.equals(userDetails.getUsername())) return;
        if (issuedBeforePasswordChange(claims.getIssuedAt(), userDetails)) return;
        authenticate(request, userDetails);
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }
}
