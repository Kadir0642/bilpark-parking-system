package com.bilpark.backend.controller;

import com.bilpark.backend.auth.CustomUserDetailsService;
import com.bilpark.backend.auth.JwtUtil;
import com.bilpark.backend.auth.PasswordService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Own account for every logged-in role (SecurityConfig: /api/me/**). Works in read-only mode too. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    public record PasswordRequest(String currentPassword, String newPassword) {}

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final CustomUserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;

    public MeController(UserRepository userRepository, PasswordService passwordService,
                        CustomUserDetailsService userDetailsService, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping
    public Map<String, Object> me(Principal principal) {
        User u = current(principal);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("fullName", u.getFullName());
        m.put("role", u.getRole().name());
        m.put("zoneId", u.getAssignedZone() != null ? u.getAssignedZone().getId() : null);
        m.put("zoneName", u.getAssignedZone() != null ? u.getAssignedZone().getZoneName() : null);
        m.put("passwordChangedAt", u.getPasswordChangedAt());
        return m;
    }

    /** Changes the own password. All older tokens stop working, so a fresh token is returned. */
    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody PasswordRequest request, Principal principal) {
        if (request == null || request.currentPassword() == null || request.newPassword() == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Mevcut ve yeni şifre gerekli.");
        }
        User u = current(principal);
        passwordService.changeOwnPassword(u, request.currentPassword(), request.newPassword());
        String token = jwtUtil.generateToken(userDetailsService.loadUserByUsername(u.getUsername()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Şifre değiştirildi.");
        body.put("token", token);
        return body;
    }

    private User current(Principal principal) {
        if (principal == null) throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Bu işlem için yetkiniz yok.");
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Kullanıcı bulunamadı."));
    }
}
