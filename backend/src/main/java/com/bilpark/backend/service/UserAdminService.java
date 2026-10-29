package com.bilpark.backend.service;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.auth.PasswordService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.repository.ZoneRepository;
import com.bilpark.backend.sync.SyncService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Admin user management: listing, creating, editing, zone assignment, activation and password reset. */
@Service
public class UserAdminService {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,50}$");
    private static final int MAX_FULL_NAME = 255;

    private final UserRepository userRepository;
    private final ZoneRepository zoneRepository;
    private final ShiftService shiftService;
    private final SyncService syncService;
    private final PasswordService passwordService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;

    public UserAdminService(UserRepository userRepository, ZoneRepository zoneRepository, ShiftService shiftService,
                            SyncService syncService, PasswordService passwordService,
                            PasswordEncoder passwordEncoder, AuditService auditService, Clock clock) {
        this.userRepository = userRepository;
        this.zoneRepository = zoneRepository;
        this.shiftService = shiftService;
        this.syncService = syncService;
        this.passwordService = passwordService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public User create(String usernameRaw, String fullNameRaw, String password, String roleRaw, Long zoneId) {
        String username = usernameRaw == null ? "" : usernameRaw.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "Kullanıcı adı 3-50 karakter olmalı; yalnızca küçük harf, rakam, nokta, tire ve alt çizgi içerebilir.");
        }
        Role role = parseRole(roleRaw);
        if (role == Role.DEVELOPER) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Geliştirici hesabı bu ekrandan oluşturulamaz.");
        }
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict(ErrorCodes.USERNAME_TAKEN, "Bu kullanıcı adı zaten kullanılıyor.");
        }
        Zone zone = null;
        if (role == Role.OFFICER) {
            if (zoneId == null) {
                throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Personel için saha seçilmelidir.");
            }
            zone = findZone(zoneId);
        }
        PasswordService.validate(username, password);
        String fullName = cleanFullName(fullNameRaw);

        User user = new User(username, passwordEncoder.encode(password), role, zone);
        user.setFullName(fullName);
        user.setActive(true);
        LocalDateTime now = LocalDateTime.now(clock);
        user.setCreatedAt(now);
        user.setPasswordChangedAt(now);
        userRepository.save(user);

        auditService.log(AuditAction.USER_CREATED, "User", user.getId(),
                "Kullanıcı oluşturuldu: " + username + " (" + role + ")", null, snapshot(user));
        return user;
    }

    /** Partial update of fullName / role / zone. Own role cannot be changed. */
    @Transactional
    public User update(Long id, String fullNameRaw, String roleRaw, Long zoneId, boolean zoneGiven,
                       String adminUsername) {
        User user = editable(id);
        Map<String, Object> before = snapshot(user);
        boolean sessionRelevant = false;

        if (fullNameRaw != null) user.setFullName(cleanFullName(fullNameRaw));

        Role newRole = roleRaw != null ? parseRole(roleRaw) : user.getRole();
        if (newRole == Role.DEVELOPER) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Geliştirici rolü atanamaz.");
        }
        if (newRole != user.getRole()) {
            if (user.getUsername().equals(adminUsername)) {
                throw ApiException.badRequest(ErrorCodes.BAD_REQUEST, "Kendi rolünüzü değiştiremezsiniz.");
            }
            sessionRelevant = true;
        }

        Zone newZone = user.getAssignedZone();
        if (zoneGiven) newZone = zoneId != null ? findZone(zoneId) : null;
        if (newRole == Role.OFFICER && newZone == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Personel için saha seçilmelidir.");
        }
        if (newRole == Role.ADMIN) newZone = null; // admins work without a zone

        Long oldZoneId = user.getAssignedZone() != null ? user.getAssignedZone().getId() : null;
        Long newZoneId = newZone != null ? newZone.getId() : null;
        if (!java.util.Objects.equals(oldZoneId, newZoneId)) sessionRelevant = true;

        if (user.getRole() == Role.OFFICER && newRole != Role.OFFICER) shiftService.closeAllOpenShifts(user);
        user.setRole(newRole);
        user.setAssignedZone(newZone);
        userRepository.save(user);

        auditService.log(AuditAction.USER_UPDATED, "User", user.getId(),
                "Kullanıcı güncellendi: " + user.getUsername(), before, snapshot(user));
        if (sessionRelevant) publishUpdated(user);
        return user;
    }

    /** Assigns an officer to a zone. Open shifts keep their zone; the next login opens a shift in the new one. */
    @Transactional
    public User assignZone(Long id, Long zoneId) {
        User user = editable(id);
        if (user.getRole() != Role.OFFICER) {
            throw ApiException.badRequest(ErrorCodes.BAD_REQUEST, "Saha ataması yalnızca personel için yapılabilir.");
        }
        if (zoneId == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "zoneId alanı gerekli.");
        }
        Zone zone = findZone(zoneId);
        Map<String, Object> before = snapshot(user);
        user.setAssignedZone(zone);
        userRepository.save(user);
        auditService.log(AuditAction.USER_ZONE_ASSIGNED, "User", user.getId(),
                user.getUsername() + " sahaya atandı: " + zone.getZoneName(), before, snapshot(user));
        publishUpdated(user);
        return user;
    }

    @Transactional
    public User setActive(Long id, boolean active, String adminUsername) {
        User user = editable(id);
        if (!active && user.getUsername().equals(adminUsername)) {
            throw ApiException.badRequest(ErrorCodes.BAD_REQUEST, "Kendi hesabınızı pasife alamazsınız.");
        }
        boolean wasActive = user.isActive();
        user.setActive(active);
        userRepository.save(user);
        if (!active && wasActive) {
            shiftService.closeAllOpenShifts(user);
            syncService.userEvent(SyncEventType.USER_DEACTIVATED, user.getId(),
                    Map.of("userId", user.getId(), "username", user.getUsername()));
            syncService.closeUserStreams(user.getId());
            auditService.log(AuditAction.USER_DEACTIVATED, "User", user.getId(),
                    "Kullanıcı pasife alındı: " + user.getUsername(), Map.of("active", true), Map.of("active", false));
        } else if (active && !wasActive) {
            auditService.log(AuditAction.USER_ACTIVATED, "User", user.getId(),
                    "Kullanıcı aktif edildi: " + user.getUsername(), Map.of("active", false), Map.of("active", true));
        }
        return user;
    }

    /** Admin resets an officer's password. Admins change their own via /api/me/password. */
    @Transactional
    public User resetPassword(Long id, String newPassword) {
        User user = editable(id);
        if (user.getRole() != Role.OFFICER) {
            throw ApiException.badRequest(ErrorCodes.PROTECTED_ACCOUNT,
                    "Yalnızca personel şifreleri sıfırlanabilir. Yönetici şifresi için sistem sağlayıcınıza başvurun.");
        }
        passwordService.resetPassword(user, newPassword, AuditAction.USER_PASSWORD_RESET,
                user.getUsername() + " şifresi sıfırlandı");
        syncService.closeUserStreams(user.getId());
        return user;
    }

    private User editable(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Kullanıcı bulunamadı."));
        if (user.getRole() == Role.DEVELOPER) {
            throw ApiException.forbidden(ErrorCodes.PROTECTED_ACCOUNT,
                    "Geliştirici hesabı bu ekrandan değiştirilemez.");
        }
        return user;
    }

    private Zone findZone(Long zoneId) {
        return zoneRepository.findById(zoneId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Saha bulunamadı."));
    }

    private static Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Rol gerekli (OFFICER veya ADMIN).");
        }
        try {
            return Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Geçersiz rol: OFFICER veya ADMIN olmalı.");
        }
    }

    private static String cleanFullName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() > MAX_FULL_NAME) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Ad soyad en fazla 255 karakter olabilir.");
        }
        return s.isEmpty() ? null : s;
    }

    private void publishUpdated(User user) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", user.getId());
        p.put("username", user.getUsername());
        p.put("zoneId", user.getAssignedZone() != null ? user.getAssignedZone().getId() : null);
        p.put("role", user.getRole().name());
        syncService.userEvent(SyncEventType.USER_UPDATED, user.getId(), p);
    }

    /** Audit snapshot: never contains the password or its hash. */
    private static Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("fullName", u.getFullName());
        m.put("role", u.getRole().name());
        m.put("zoneId", u.getAssignedZone() != null ? u.getAssignedZone().getId() : null);
        return m;
    }
}
