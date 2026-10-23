package com.bilpark.backend.auth;

import com.bilpark.backend.common.DeviceIds;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.service.ShiftService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final ShiftService shiftService;

    public AuthController(AuthenticationManager authenticationManager,
                          CustomUserDetailsService userDetailsService,
                          JwtUtil jwtUtil,
                          UserRepository userRepository,
                          ShiftService shiftService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.shiftService = shiftService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Kullanıcı adı veya şifre hatalı.", "code", ErrorCodes.UNAUTHORIZED));
        }

        final UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
        final String token = jwtUtil.generateToken(userDetails);

        User user = userRepository.findByUsername(request.username()).orElseThrow();

        user.setLastLoginTime(java.time.LocalDateTime.now());
        userRepository.save(user);

        // Officers get a shift per device; admin has none
        Shift shift = shiftService.openShift(user, DeviceIds.sanitize(deviceHeader));

        return ResponseEntity.ok(new LoginResponse(
                token,
                user.getUsername(),
                user.getRole().name(),
                user.getAssignedZone(),
                shift != null ? shift.getId() : null
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(java.security.Principal principal,
                                    @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader) {
        if (principal != null) {
            User user = userRepository.findByUsername(principal.getName()).orElse(null);
            if (user != null) {
                shiftService.closeShift(user, DeviceIds.sanitize(deviceHeader));
                user.setLastLoginTime(null);
                userRepository.save(user);
            }
        }
        return ResponseEntity.ok(Map.of("message", "Çıkış yapıldı, vardiya kapatıldı."));
    }
}
