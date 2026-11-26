package com.bilpark.backend.auth;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.Overload;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.service.ShiftService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Login while the database is unreachable is not "wrong password": the error goes to the 503 handler. */
class AuthControllerOverloadTest {

    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final AuditService audit = mock(AuditService.class);
    private final AuthController controller = new AuthController(manager, mock(CustomUserDetailsService.class),
            mock(JwtUtil.class), mock(UserRepository.class), mock(ShiftService.class), audit);

    @Test
    void databaseFailureDuringLoginIsRethrownForThe503Handler() {
        var ex = new InternalAuthenticationServiceException("lookup failed",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available"));
        when(manager.authenticate(any())).thenThrow(ex);

        var thrown = assertThrows(InternalAuthenticationServiceException.class,
                () -> controller.login(new LoginRequest("tevfik", "x"), null));
        assertTrue(Overload.isDatabaseUnavailable(thrown));
        verifyNoInteractions(audit);
    }

    @Test
    void wrongPasswordStays401AndAudited() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
        var r = controller.login(new LoginRequest("tevfik", "x"), null);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        verify(audit).logAsName(eq("tevfik"), any(), any(), any(), any(), any(), any());
    }
}
