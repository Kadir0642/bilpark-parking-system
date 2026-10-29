package com.bilpark.backend.audit;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.AuditLog;
import com.bilpark.backend.repository.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read side of the audit log (admin list, filters and export). */
@Service
public class AuditQueryService {

    public static final int EXPORT_LIMIT = 50_000;

    /** Raw filter values as they arrive in the query string. */
    public record Filter(String from, String to, String action, String actor, String actorRole,
                         String entityType, String entityId, String q,
                         boolean includeMaintenance) {}

    private static final Map<AuditAction, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put(AuditAction.LOGIN, "Giriş");
        LABELS.put(AuditAction.LOGIN_FAILED, "Başarısız giriş");
        LABELS.put(AuditAction.LOGOUT, "Çıkış");
        LABELS.put(AuditAction.PASSWORD_CHANGED, "Şifre değiştirildi");
        LABELS.put(AuditAction.VEHICLE_CHECK_IN, "Araç girişi");
        LABELS.put(AuditAction.VEHICLE_CHECK_OUT, "Araç çıkışı");
        LABELS.put(AuditAction.VEHICLE_RUNAWAY, "Kaçak araç");
        LABELS.put(AuditAction.SPOT_CANCELLED, "Giriş iptali");
        LABELS.put(AuditAction.RECORD_CANCELLED, "Kayıt iptali");
        LABELS.put(AuditAction.TARIFF_DRAFT_CREATED, "Tarife taslağı oluşturuldu");
        LABELS.put(AuditAction.TARIFF_DRAFT_UPDATED, "Tarife taslağı güncellendi");
        LABELS.put(AuditAction.TARIFF_SCHEDULED, "Tarife zamanlandı");
        LABELS.put(AuditAction.TARIFF_CANCELLED, "Tarife iptal edildi");
        LABELS.put(AuditAction.TARIFF_ACTIVATED, "Tarife yürürlüğe girdi");
        LABELS.put(AuditAction.SUBSCRIPTION_CREATED, "Abonelik oluşturuldu");
        LABELS.put(AuditAction.SUBSCRIPTION_CANCELLED, "Abonelik iptal edildi");
        LABELS.put(AuditAction.USER_CREATED, "Kullanıcı oluşturuldu");
        LABELS.put(AuditAction.USER_UPDATED, "Kullanıcı güncellendi");
        LABELS.put(AuditAction.USER_ZONE_ASSIGNED, "Saha ataması");
        LABELS.put(AuditAction.USER_ACTIVATED, "Kullanıcı etkinleştirildi");
        LABELS.put(AuditAction.USER_DEACTIVATED, "Kullanıcı devre dışı bırakıldı");
        LABELS.put(AuditAction.USER_PASSWORD_RESET, "Şifre sıfırlandı");
        LABELS.put(AuditAction.FISCAL_YEAR_CREATED, "Dönem oluşturuldu");
        LABELS.put(AuditAction.FISCAL_YEAR_ARCHIVED, "Dönem arşivlendi");
        LABELS.put(AuditAction.FISCAL_YEAR_DELETED, "Dönem silindi");
        LABELS.put(AuditAction.SETTINGS_UPDATED, "Ayar güncellendi");
        LABELS.put(AuditAction.LICENSE_UPDATED, "Lisans güncellendi");
        LABELS.put(AuditAction.REPORT_EXPORTED, "Rapor indirildi");
        LABELS.put(AuditAction.RETENTION_RUN, "Saklama süresi işlemi");
        LABELS.put(AuditAction.DEVELOPER_ACCOUNT_CREATED, "Geliştirici hesabı oluşturuldu");
        LABELS.put(AuditAction.DEV_HEALTH_VIEWED, "Geliştirici: sağlık görüntülendi");
        LABELS.put(AuditAction.DEV_ERRORS_VIEWED, "Geliştirici: hatalar görüntülendi");
        LABELS.put(AuditAction.DEV_AUDIT_VIEWED, "Geliştirici: denetim kaydı görüntülendi");
        LABELS.put(AuditAction.DEV_ADMIN_PASSWORD_RESET, "Geliştirici: yönetici şifresi sıfırlandı");
        LABELS.put(AuditAction.DEV_ANNOUNCEMENT_CREATED, "Geliştirici: duyuru gönderildi");
    }

    private final AuditLogRepository repository;
    private final JsonMapper mapper;
    private final Clock clock;

    public AuditQueryService(AuditLogRepository repository, JsonMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Every AuditAction with a Turkish label (filter dropdown). */
    public List<Map<String, String>> actions() {
        List<Map<String, String>> out = new ArrayList<>();
        for (AuditAction a : AuditAction.values()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("action", a.name());
            m.put("label", LABELS.getOrDefault(a, a.name()));
            out.add(m);
        }
        return out;
    }

    public static String labelOf(AuditAction a) {
        return a == null ? null : LABELS.getOrDefault(a, a.name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Filter f, int page, int size) {
        int s = Math.max(1, Math.min(size, 200));
        int p = Math.max(0, page);
        Page<AuditLog> result = repository.findAll(spec(f), PageRequest.of(p, s, order()));
        long hidden = includesMaintenance(f) ? 0 : repository.count(spec(f, true)) - result.getTotalElements();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.getContent().stream().map(this::toMap).toList());
        body.put("page", p);
        body.put("size", s);
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("maintenanceHidden", Math.max(0, hidden));
        return body;
    }

    @Transactional(readOnly = true)
    public List<AuditLog> forExport(Filter f) {
        return repository.findAll(spec(f), PageRequest.of(0, EXPORT_LIMIT, order())).getContent();
    }

    private static Sort order() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    public Map<String, Object> toMap(AuditLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("createdAt", a.getCreatedAt());
        m.put("actorUsername", a.getActorUsername());
        m.put("actorRole", a.getActorRole());
        m.put("deviceId", a.getDeviceId());
        m.put("ipAddress", a.getIpAddress());
        m.put("action", a.getAction() != null ? a.getAction().name() : null);
        m.put("entityType", a.getEntityType());
        m.put("entityId", a.getEntityId());
        m.put("summary", a.getSummary());
        m.put("before", parse(a.getBeforeData()));
        m.put("after", parse(a.getAfterData()));
        return m;
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Object.class);
        } catch (RuntimeException e) {
            return json;
        }
    }

    /** Maintenance rows: DEVELOPER actors, DEV_* actions and DEVELOPER_ACCOUNT_CREATED. */
    private static final EnumSet<AuditAction> MAINTENANCE_ACTIONS = EnumSet.noneOf(AuditAction.class);
    static {
        for (AuditAction a : AuditAction.values()) {
            if (a.name().startsWith("DEV_") || a == AuditAction.DEVELOPER_ACCOUNT_CREATED) MAINTENANCE_ACTIONS.add(a);
        }
    }

    /** Asking for actorRole=DEVELOPER or a maintenance action explicitly counts as includeMaintenance=true. */
    private static boolean includesMaintenance(Filter f) {
        if (f.includeMaintenance()) return true;
        if (has(f.actorRole()) && f.actorRole().trim().equalsIgnoreCase("DEVELOPER")) return true;
        return has(f.action()) && MAINTENANCE_ACTIONS.stream()
                .anyMatch(a -> a.name().equalsIgnoreCase(f.action().trim()));
    }

    private Specification<AuditLog> spec(Filter f) {
        return spec(f, includesMaintenance(f));
    }

    private Specification<AuditLog> spec(Filter f, boolean withMaintenance) {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = parseDate(f.to(), "to", today);
        LocalDate from = parseDate(f.from(), "from", to.minusDays(30));
        if (from.isAfter(to)) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Başlangıç tarihi bitişten sonra olamaz.");
        }
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        EnumSet<AuditAction> actions = parseActions(f.action());

        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), start));
            ps.add(cb.lessThan(root.<LocalDateTime>get("createdAt"), end));
            if (!withMaintenance) {
                ps.add(cb.or(cb.isNull(root.get("actorRole")), cb.notEqual(root.get("actorRole"), "DEVELOPER")));
                ps.add(cb.not(root.get("action").in(MAINTENANCE_ACTIONS)));
            }
            if (!actions.isEmpty()) ps.add(root.get("action").in(actions));
            if (has(f.actor())) {
                ps.add(cb.like(cb.lower(root.<String>get("actorUsername")), like(f.actor()), '\\'));
            }
            if (has(f.actorRole())) {
                ps.add(cb.equal(root.get("actorRole"), f.actorRole().trim().toUpperCase(Locale.ROOT)));
            }
            if (has(f.entityType())) ps.add(cb.equal(root.get("entityType"), f.entityType().trim()));
            if (has(f.entityId())) ps.add(cb.equal(root.get("entityId"), f.entityId().trim()));
            if (has(f.q())) ps.add(cb.like(cb.lower(root.<String>get("summary")), like(f.q()), '\\'));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    // lower-cased "%term%" with LIKE wildcards escaped (Locale.ROOT: the Turkish dotless-i must not leak in)
    private static String like(String term) {
        String t = term.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + t + "%";
    }

    private static LocalDate parseDate(String s, String name, LocalDate fallback) {
        if (!has(s)) return fallback;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "Geçersiz tarih (" + name + "): yyyy-MM-dd biçiminde olmalıdır.");
        }
    }

    private static EnumSet<AuditAction> parseActions(String raw) {
        EnumSet<AuditAction> set = EnumSet.noneOf(AuditAction.class);
        if (!has(raw)) return set;
        for (String part : raw.split(",")) {
            if (part.isBlank()) continue;
            try {
                set.add(AuditAction.valueOf(part.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Geçersiz işlem türü: " + part.trim());
            }
        }
        return set;
    }
}
