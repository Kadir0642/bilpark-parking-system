package com.bilpark.backend.developer;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.audit.ErrorLogService;
import com.bilpark.backend.auth.PasswordService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.license.LicenseService;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.ErrorLog;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.bilpark.backend.repository.ShiftRepository;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.notification.NotificationService;
import com.bilpark.backend.settings.SettingsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The developer's (maintenance) powers: license dates, system health, recent errors, admin password reset.
 * Nothing else. Every call is audited and visible to the admin.
 */
@Service
public class DeveloperService {

    private static final java.time.format.DateTimeFormatter TR_DATE =
            java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("tr"));

    private final LicenseService licenseService;
    private final AuditService auditService;
    private final ErrorLogService errorLogService;
    private final PasswordService passwordService;
    private final SettingsService settingsService;
    private final UserRepository userRepository;
    private final ParkSpotRepository spotRepository;
    private final ParkingRecordRepository recordRepository;
    private final ShiftRepository shiftRepository;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Clock clock;
    private final NotificationService notificationService;

    public DeveloperService(LicenseService licenseService, AuditService auditService, ErrorLogService errorLogService,
                            PasswordService passwordService, SettingsService settingsService,
                            UserRepository userRepository, ParkSpotRepository spotRepository,
                            ParkingRecordRepository recordRepository, ShiftRepository shiftRepository,
                            JdbcTemplate jdbc, JsonMapper mapper, Clock clock,
                            NotificationService notificationService) {
        this.notificationService = notificationService;
        this.licenseService = licenseService;
        this.auditService = auditService;
        this.errorLogService = errorLogService;
        this.passwordService = passwordService;
        this.settingsService = settingsService;
        this.userRepository = userRepository;
        this.spotRepository = spotRepository;
        this.recordRepository = recordRepository;
        this.shiftRepository = shiftRepository;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public LicenseService.LicenseStatus updateLicense(LocalDate expiresOn, Integer graceDays, String licensee, String note) {
        if (expiresOn == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "expiresOn alanı gerekli (yyyy-MM-dd).");
        }
        LicenseService.LicenseStatus st = licenseService.update(expiresOn, graceDays, licensee, note);
        notificationService.create(NotificationService.LICENSE_UPDATED,
                "Lisans güncellendi: bitiş " + TR_DATE.format(st.expiresOn()),
                "Lisans tarihleri geliştirici tarafından güncellendi.", null, createdBy());
        return st;
    }

    /** Never throws because of a database problem: database.ok=false instead. */
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("usedMb", used / (1024 * 1024));
        mem.put("maxMb", rt.maxMemory() / (1024 * 1024));

        Map<String, Object> db = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        boolean dbOk = false;
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            dbOk = one != null && one == 1;
        } catch (RuntimeException ignored) {
            // reported as ok=false
        }
        db.put("ok", dbOk);
        db.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);

        h.put("status", dbOk ? "UP" : "DEGRADED");
        h.put("appVersion", appVersion());
        h.put("startedAt", startedAt());
        h.put("time", LocalDateTime.now(clock));
        h.put("uptimeSeconds", uptimeSeconds());
        h.put("javaVersion", System.getProperty("java.version"));
        h.put("memory", mem);
        h.put("database", db);
        h.put("flywayVersion", dbOk ? flywayVersion() : null);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("activeSpots", dbOk ? safe(spotRepository::count) : null);
        counts.put("parkingRecords", dbOk ? safe(recordRepository::count) : null);
        counts.put("users", dbOk ? safe(userRepository::count) : null);
        counts.put("openShifts", dbOk ? safe(shiftRepository::countByEndTimeIsNull) : null);
        h.put("counts", counts);

        h.put("license", dbOk ? safe(licenseService::status) : null);
        h.put("errorsLast24h", dbOk ? errorLogService.countSince(LocalDateTime.now(clock).minusHours(24)) : -1);
        h.put("lastRetentionRun", dbOk ? lastRetentionRun() : null);

        try {
            auditService.log(AuditAction.DEV_HEALTH_VIEWED, "System", "health",
                    "Geliştirici sistem durumunu görüntüledi", null, null);
        } catch (RuntimeException ignored) {
            // database is down: the audit row cannot be written, the health answer must still be returned
        }
        return h;
    }

    public List<Map<String, Object>> errors(int limit) {
        List<ErrorLog> rows = errorLogService.recent(limit);
        auditService.log(AuditAction.DEV_ERRORS_VIEWED, "System", "errors",
                "Geliştirici son hataları görüntüledi", null, Map.of("limit", limit));
        return rows.stream().map(DeveloperService::errorMap).toList();
    }

    /** Resets an ADMIN's password (the admin cannot do it for others, nobody else can for the admin). */
    public Map<String, Object> resetAdminPassword(String usernameRaw, String newPassword) {
        if (usernameRaw == null || usernameRaw.isBlank() || newPassword == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "username ve newPassword gerekli.");
        }
        User target = userRepository.findByUsername(usernameRaw.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Kullanıcı bulunamadı."));
        if (target.getRole() != Role.ADMIN) {
            throw ApiException.badRequest(ErrorCodes.PROTECTED_ACCOUNT,
                    "Bu uç nokta yalnızca yönetici hesaplarının şifresini sıfırlar.");
        }
        passwordService.resetPassword(target, newPassword, AuditAction.DEV_ADMIN_PASSWORD_RESET,
                "Geliştirici yönetici şifresini sıfırladı: " + target.getUsername());
        notificationService.create(NotificationService.ADMIN_PASSWORD_RESET,
                target.getUsername() + " hesabının şifresi sıfırlandı",
                "Şifre geliştirici tarafından sıfırlandı. İlk girişte şifrenizi değiştirin.", null, createdBy());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", "Yönetici şifresi sıfırlandı.");
        m.put("username", target.getUsername());
        return m;
    }

    private static String createdBy() {
        org.springframework.security.core.Authentication a =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getName() != null ? a.getName() : AuditService.SYSTEM;
    }

    private static Map<String, Object> errorMap(ErrorLog e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("createdAt", e.getCreatedAt());
        m.put("method", e.getMethod());
        m.put("path", e.getPath());
        m.put("errorCode", e.getErrorCode());
        m.put("exceptionClass", e.getExceptionClass());
        m.put("message", e.getMessage());
        m.put("stackExcerpt", e.getStackExcerpt());
        m.put("username", e.getUsername());
        return m;
    }

    private long uptimeSeconds() {
        long startMs = ManagementFactory.getRuntimeMXBean().getStartTime();
        return Math.max(0, Duration.between(Instant.ofEpochMilli(startMs), Instant.now()).toSeconds());
    }

    /** Deploy commit (Render sets RENDER_GIT_COMMIT, others GIT_COMMIT) or the jar build version; null when unknown. */
    static String appVersion() {
        for (String key : new String[]{"RENDER_GIT_COMMIT", "GIT_COMMIT", "SOURCE_VERSION"}) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) return v.trim().length() > 12 ? v.trim().substring(0, 12) : v.trim();
        }
        String impl = DeveloperService.class.getPackage().getImplementationVersion();
        return impl == null || impl.isBlank() ? null : impl;
    }

    private LocalDateTime startedAt() {
        long startMs = ManagementFactory.getRuntimeMXBean().getStartTime();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), clock.getZone());
    }

    private String flywayVersion() {
        try {
            List<String> v = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = true "
                    + "AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class);
            return v.isEmpty() ? null : v.get(0);
        } catch (RuntimeException e) {
            return null; // no flyway table (H2 tests) or not readable
        }
    }

    private Object lastRetentionRun() {
        try {
            return settingsService.get(SettingsService.KEY_RETENTION_LAST_RUN).map(s -> {
                try {
                    return (Object) mapper.readValue(s, Object.class);
                } catch (RuntimeException e) {
                    return s;
                }
            }).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> T safe(Supplier<T> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
