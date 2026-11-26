package com.bilpark.backend.auth;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.DeviceIds;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.common.RateLimitedException;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.service.ShiftService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final ShiftService shiftService;
    private final AuditService auditService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthenticationManager authenticationManager,
                          CustomUserDetailsService userDetailsService,
                          JwtUtil jwtUtil,
                          UserRepository userRepository,
                          ShiftService shiftService,
                          AuditService auditService,
                          LoginRateLimiter loginRateLimiter) {
        this.auditService = auditService;
        this.loginRateLimiter = loginRateLimiter;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.shiftService = shiftService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
                                   HttpServletRequest httpRequest) {
        // 429 while the username is locked or the client IP is over its cap; the password is not checked then
        LoginRateLimiter.Attempt attempt = loginRateLimiter.begin(httpRequest, request.username());
        if (attempt.refusal() != null) throw refused(attempt.refusal(), request.username());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
            loginRateLimiter.succeeded(attempt);
        } catch (AuthenticationException e) {
            // The user could not be read (pool timeout, database down): not a wrong password. 503 + Retry-After,
            // no LOGIN_FAILED audit row (that write would wait for a connection too), no failure counted.
            if (com.bilpark.backend.common.Overload.isDatabaseUnavailable(e)) throw e;
            LoginRateLimiter.Refusal lock = loginRateLimiter.failed(attempt);
            auditService.logAsName(request.username(), AuditAction.LOGIN_FAILED, "User", null,
                    "Başarısız giriş denemesi", null, null);
            if (lock != null) throw refused(lock, request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Kullanıcı adı veya şifre hatalı.", "code", ErrorCodes.UNAUTHORIZED));
        } finally {
            loginRateLimiter.abandon(attempt); // no-op when failed/succeeded already settled it
        }

        final UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
        final String token = jwtUtil.generateToken(userDetails);

        User user = userRepository.findByUsername(request.username()).orElseThrow();

        user.setLastLoginTime(java.time.LocalDateTime.now());
        userRepository.save(user);

        // Officers get a shift per device; admin has none
        Shift shift = shiftService.openShift(user, DeviceIds.sanitize(deviceHeader));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deviceId", DeviceIds.sanitize(deviceHeader));
        after.put("shiftId", shift != null ? shift.getId() : null);
        auditService.logAs(user, AuditAction.LOGIN, "User", user.getId(),
                user.getUsername() + " giriş yaptı", null, after);

        return ResponseEntity.ok(new LoginResponse(
                token,
                user.getUsername(),
                user.getRole().name(),
                user.getAssignedZone(),
                shift != null ? shift.getId() : null
        ));
    }

    /** 429 RATE_LIMITED + Retry-After; one LOGIN_LOCKED audit row per lock / per IP window (no password). */
    private RateLimitedException refused(LoginRateLimiter.Refusal r, String username) {
        if (r.report()) {
            Map<String, Object> after = new LinkedHashMap<>();
            String summary;
            if (r.reason() == LoginRateLimiter.Reason.IP_LIMIT) {
                after.put("reason", "ip");
                summary = "Bu IP adresinden çok fazla giriş denemesi: girişler geçici olarak durduruldu";
            } else {
                after.put("reason", "username");
                after.put("failures", r.failures());
                after.put("lockSeconds", r.lockSeconds());
                summary = r.failures() + " başarısız denemeden sonra giriş " + Math.max(1, (r.lockSeconds() + 59) / 60)
                        + " dakika kilitlendi";
            }
            try {
                auditService.logAsName(username, AuditAction.LOGIN_LOCKED, "User", null, summary, null, after);
            } catch (RuntimeException ex) {
                log.warn("Giriş kilidi denetim kaydına yazılamadı: {}", ex.toString());
            }
        }
        return new RateLimitedException(r.message(), r.retryAfterSeconds());
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(java.security.Principal principal,
                                    @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader) {
        if (principal != null) {
            User user = userRepository.findByUsername(principal.getName()).orElse(null);
            if (user != null) {
                shiftService.closeShift(user, DeviceIds.sanitize(deviceHeader));
                auditService.logAs(user, AuditAction.LOGOUT, "User", user.getId(),
                        user.getUsername() + " çıkış yaptı", null, null);
                user.setLastLoginTime(null);
                userRepository.save(user);
            }
        }
        return ResponseEntity.ok(Map.of("message", "Çıkış yapıldı, vardiya kapatıldı."));
    }
}
