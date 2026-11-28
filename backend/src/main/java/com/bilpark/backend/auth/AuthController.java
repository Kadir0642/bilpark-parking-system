package com.bilpark.backend.auth;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.DeviceIds;
import com.bilpark.backend.common.ErrorCodes;
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
        // 429 while this username is locked at this address or the address is over its cap; no password check then
        LoginRateLimiter.Attempt attempt = loginRateLimiter.begin(httpRequest, request.username());
        if (attempt.refusal() != null) return refused(attempt.refusal(), request.username());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
            loginRateLimiter.succeeded(attempt);
        } catch (AuthenticationException e) {
            // The user could not be read (pool timeout, database down): not a wrong password. 503 + Retry-After,
            // no LOGIN_FAILED audit row (that write would wait for a connection too), no failure counted.
            if (com.bilpark.backend.common.Overload.isDatabaseUnavailable(e)) throw e;
            LoginRateLimiter.Failure failure = loginRateLimiter.failed(attempt);
            auditService.logAsName(request.username(), AuditAction.LOGIN_FAILED, "User", null,
                    "Başarısız giriş denemesi", null, null);
            if (failure.alert()) {
                audit(request.username(), AuditAction.LOGIN_FAILURES_HIGH,
                        "Bu kullanıcı adına kısa sürede " + failure.usernameFailures() + " hatalı giriş denemesi (kilit yok)",
                        Map.of("failures", failure.usernameFailures()));
            }
            if (failure.lock() != null) return refused(failure.lock(), request.username());
            return wrongPassword(failure.remainingAttempts());
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

    static final String WRONG_PASSWORD = "Kullanıcı adı veya şifre hatalı.";
    /** At most this many attempts left: the 401 says how many (the same for unknown usernames). */
    static final int SHOW_REMAINING_AT = 2;

    private static ResponseEntity<Map<String, Object>> wrongPassword(int remaining) {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean show = remaining >= 1 && remaining <= SHOW_REMAINING_AT;
        body.put("error", show ? WRONG_PASSWORD + " " + remaining + " deneme hakkınız kaldı." : WRONG_PASSWORD);
        body.put("code", ErrorCodes.UNAUTHORIZED);
        if (show) body.put("remainingAttempts", remaining);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * 429 + Retry-After, body {error, code, retryAfterSeconds}: code LOGIN_LOCKED (this username at this address)
     * or RATE_LIMITED (this address). One LOGIN_LOCKED audit row per lock / per IP window, never a password.
     */
    private ResponseEntity<Map<String, Object>> refused(LoginRateLimiter.Refusal r, String username) {
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
                summary = r.failures() + " hatalı denemeden sonra bu adresten giriş "
                        + Math.max(1, (r.lockSeconds() + 59) / 60) + " dakika kilitlendi";
            }
            audit(username, AuditAction.LOGIN_LOCKED, summary, after);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", r.message());
        body.put("code", r.code());
        body.put("retryAfterSeconds", r.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(r.retryAfterSeconds())).body(body);
    }

    private void audit(String username, AuditAction action, String summary, Map<String, Object> after) {
        try {
            auditService.logAsName(username, action, "User", null, summary, null, after);
        } catch (RuntimeException ex) {
            log.warn("Giriş sınırı denetim kaydına yazılamadı: {}", ex.toString());
        }
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
