package com.bilpark.backend.auth;

import com.bilpark.backend.sync.TicketService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A valid token must never turn into 401 because the database is slow: 503 + Retry-After instead. */
class JwtAuthFilterTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final CustomUserDetailsService users = mock(CustomUserDetailsService.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, users, mock(TicketService.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/parking/spots");
        req.addHeader("Authorization", "Bearer abc.def.ghi");
        return req;
    }

    private void validToken() {
        when(jwtUtil.parseClaims("abc.def.ghi")).thenReturn(Jwts.claims().subject("tevfik")
                .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).build());
    }

    @Test
    void databaseFailureDuringLookupAnswers503WithRetryAfter() throws Exception {
        validToken();
        when(users.loadForToken("tevfik")).thenThrow(new CannotCreateTransactionException("no connection",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), res, chain);

        assertEquals(503, res.getStatus());
        assertEquals("5", res.getHeader("Retry-After"));
        assertTrue(res.getContentAsString().contains("\"code\":\"SERVICE_UNAVAILABLE\""));
        assertNull(chain.getRequest(), "the request must not continue (it would become 401)");
    }

    @Test
    void validTokenAuthenticates() throws Exception {
        validToken();
        when(users.loadForToken("tevfik")).thenReturn(new BilparkUserDetails("tevfik", "", true,
                List.of(new SimpleGrantedAuthority("ROLE_OFFICER")), null));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), res, chain);

        assertNotNull(chain.getRequest());
        assertEquals("tevfik", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void unknownUserStaysAnonymous() throws Exception {
        validToken();
        when(users.loadForToken("tevfik")).thenThrow(new UsernameNotFoundException("tevfik"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), res, chain);

        assertNotNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void passiveUserStaysAnonymous() throws Exception {
        validToken();
        when(users.loadForToken("tevfik")).thenReturn(new BilparkUserDetails("tevfik", "", false,
                List.of(new SimpleGrantedAuthority("ROLE_OFFICER")), null));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenIssuedBeforePasswordChangeStaysAnonymous() throws Exception {
        validToken(); // iat = 60 s ago
        when(users.loadForToken("tevfik")).thenReturn(new BilparkUserDetails("tevfik", "", true,
                List.of(new SimpleGrantedAuthority("ROLE_OFFICER")), java.time.Instant.now()));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void invalidTokenStaysAnonymousWithoutLookup() throws Exception {
        when(jwtUtil.parseClaims("abc.def.ghi")).thenThrow(new MalformedJwtException("bad"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(users);
    }
}
