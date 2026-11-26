package com.bilpark.backend.auth;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ClientIp;
import com.bilpark.backend.common.Overload;
import com.bilpark.backend.common.RateLimitedException;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.service.ShiftService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

import java.sql.SQLTransientConnectionException;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Login while the database is unreachable is not "wrong password": the error goes to the 503 handler. */
class AuthControllerOverloadTest {

    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final AuditService audit = mock(AuditService.class);
    // 3 failures lock the username; 100 attempts per IP
    private final LoginRateLimiter limiter = new LoginRateLimiter(true, 3, 900, "60", 100, 60, 1000, 1000,
            new ClientIp(1), Clock.systemUTC());
    private final AuthController controller = new AuthController(manager, mock(CustomUserDetailsService.class),
            mock(JwtUtil.class), mock(UserRepository.class), mock(ShiftService.class), audit, limiter);

    private static InternalAuthenticationServiceException databaseDown() {
        return new InternalAuthenticationServiceException("lookup failed",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available"));
    }

    @Test
    void databaseFailureDuringLoginIsRethrownForThe503Handler() {
        when(manager.authenticate(any())).thenThrow(databaseDown());

        var thrown = assertThrows(InternalAuthenticationServiceException.class,
                () -> controller.login(new LoginRequest("tevfik", "x"), null, new MockHttpServletRequest()));
        assertTrue(Overload.isDatabaseUnavailable(thrown));
        verifyNoInteractions(audit);
    }

    @Test
    void databaseFailuresAreNotCountedAsFailedLogins() {
        when(manager.authenticate(any())).thenThrow(databaseDown());
        for (int i = 0; i < 10; i++) {
            assertThrows(InternalAuthenticationServiceException.class,
                    () -> controller.login(new LoginRequest("tevfik", "x"), null, new MockHttpServletRequest()));
        }
        verifyNoInteractions(audit);

        // the database is back: the first wrong password is an ordinary 401, not a lock
        reset(manager);
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
        var r = controller.login(new LoginRequest("tevfik", "x"), null, new MockHttpServletRequest());
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    @Test
    void wrongPasswordStays401AndAudited() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
        var r = controller.login(new LoginRequest("tevfik", "x"), null, new MockHttpServletRequest());
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        verify(audit).logAsName(eq("tevfik"), eq(AuditAction.LOGIN_FAILED), any(), any(), any(), any(), any());
    }

    @Test
    void lockIsAuditedOnceWithoutThePassword() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
        controller.login(new LoginRequest("tevfik", "secret-guess-1"), null, new MockHttpServletRequest());
        controller.login(new LoginRequest("tevfik", "secret-guess-2"), null, new MockHttpServletRequest());
        var locked = assertThrows(RateLimitedException.class,
                () -> controller.login(new LoginRequest("tevfik", "secret-guess-3"), null, new MockHttpServletRequest()));
        assertEquals(60, locked.getRetryAfterSeconds());
        assertThrows(RateLimitedException.class,
                () -> controller.login(new LoginRequest("tevfik", "secret-guess-4"), null, new MockHttpServletRequest()));

        verify(audit, times(3)).logAsName(eq("tevfik"), eq(AuditAction.LOGIN_FAILED), any(), any(), any(), any(), any());
        verify(audit, times(1)).logAsName(eq("tevfik"), eq(AuditAction.LOGIN_LOCKED), eq("User"), isNull(),
                argThat(s -> s.contains("3 başarısız") && !s.contains("secret")), isNull(),
                argThat(after -> !after.toString().contains("secret")));
        // the refused attempt never reached the password check
        verify(manager, times(3)).authenticate(any());
    }
}
