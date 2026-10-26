package com.bilpark.backend.auth;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * Password rules and the only places a password is set after seeding.
 * Setting a password stamps users.password_changed_at: every JWT issued before that second
 * is rejected by JwtAuthFilter, so a reset logs the user out on all devices.
 */
@Service
public class PasswordService {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72; // BCrypt limit

    public static final String RULES = "Şifre en az " + MIN_LENGTH + " karakter olmalı, en az bir harf ve bir rakam "
            + "içermeli ve kullanıcı adını içermemeli.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;

    public PasswordService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           AuditService auditService, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Throws 400 WEAK_PASSWORD when the password breaks a rule. */
    public static void validate(String username, String password) {
        if (password == null || password.length() < MIN_LENGTH) throw weak(RULES);
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw weak("Şifre en fazla " + MAX_BYTES + " bayt olabilir.");
        }
        if (!password.trim().equals(password)) throw weak("Şifre boşlukla başlayamaz veya bitemez.");
        boolean letter = password.codePoints().anyMatch(Character::isLetter);
        boolean digit = password.codePoints().anyMatch(Character::isDigit);
        if (!letter || !digit) throw weak(RULES);
        if (username != null && !username.isBlank()
                && password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
            throw weak("Şifre kullanıcı adını içeremez.");
        }
        if (password.chars().distinct().count() < 4) throw weak("Şifre çok basit; daha çeşitli karakterler kullanın.");
    }

    private static ApiException weak(String msg) {
        return ApiException.badRequest(ErrorCodes.WEAK_PASSWORD, msg);
    }

    /** Logged-in user changes their own password (current password verified). Audited as PASSWORD_CHANGED. */
    @Transactional
    public User changeOwnPassword(User user, String currentPassword, String newPassword) {
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw ApiException.badRequest(ErrorCodes.PASSWORD_MISMATCH, "Mevcut şifre hatalı.");
        }
        if (newPassword != null && passwordEncoder.matches(newPassword, user.getPassword())) {
            throw weak("Yeni şifre mevcut şifreyle aynı olamaz.");
        }
        validate(user.getUsername(), newPassword);
        apply(user, newPassword);
        auditService.logAs(user, AuditAction.PASSWORD_CHANGED, "User", user.getId(),
                user.getUsername() + " kendi şifresini değiştirdi", null, null);
        return user;
    }

    /**
     * Someone else sets the user's password (admin resets an officer, developer resets an admin).
     * The caller passes the audit action; the password itself is never logged.
     */
    @Transactional
    public User resetPassword(User target, String newPassword, AuditAction action, String summary) {
        validate(target.getUsername(), newPassword);
        apply(target, newPassword);
        auditService.log(action, "User", target.getId(), summary, null, Map.of("username", target.getUsername()));
        return target;
    }

    private void apply(User user, String newPassword) {
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now(clock));
        userRepository.save(user);
    }
}
