package com.bilpark.backend.developer;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AdminNotification;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The developer's announcements to the admin (docs/api-bildirimler.md). No edit, no delete. Audited. */
@Service
public class AnnouncementService {

    public record Request(String type, String title, String body, String validUntil) {}

    private final NotificationService notifications;
    private final AuditService auditService;
    private final Clock clock;

    public AnnouncementService(NotificationService notifications, AuditService auditService, Clock clock) {
        this.notifications = notifications;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> create(Request r, String username) {
        if (r == null) throw invalid("İstek gövdesi gerekli.");
        String type = r.type() == null ? "" : r.type().trim().toUpperCase(Locale.ROOT);
        if (!type.equals(NotificationService.ANNOUNCEMENT) && !type.equals(NotificationService.MAINTENANCE)) {
            throw invalid("type ANNOUNCEMENT veya MAINTENANCE olmalı.");
        }
        String title = r.title() == null ? "" : r.title().trim();
        if (title.length() < 3 || title.length() > 120) throw invalid("Başlık 3-120 karakter olmalı.");
        String body = r.body() == null ? null : r.body().trim();
        if (body != null && body.length() > 2000) throw invalid("Metin en fazla 2000 karakter olabilir.");

        LocalDateTime validUntil = null;
        if (type.equals(NotificationService.MAINTENANCE)) {
            if (r.validUntil() == null || r.validUntil().isBlank()) {
                throw invalid("Planlı bakım için validUntil zorunlu.");
            }
            validUntil = parseValidUntil(r.validUntil().trim());
            if (!validUntil.isAfter(LocalDateTime.now(clock))) throw invalid("validUntil gelecekte olmalı.");
        }

        AdminNotification n = notifications.create(type, title, body, validUntil, username);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", type);
        after.put("title", title);
        after.put("validUntil", validUntil);
        auditService.log(AuditAction.DEV_ANNOUNCEMENT_CREATED, "Notification", n.getId(),
                "Geliştirici duyuru gönderdi: " + title, null, after);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", n.getId());
        out.put("type", n.getType());
        out.put("title", n.getTitle());
        out.put("body", n.getBody());
        out.put("createdAt", n.getCreatedAt());
        out.put("validUntil", n.getValidUntil());
        return out;
    }

    public List<Map<String, Object>> list(int limit) {
        return notifications.sent(limit);
    }

    private static final java.time.ZoneId ISTANBUL = java.time.ZoneId.of("Europe/Istanbul");

    /** Local ISO (no zone) is Istanbul time; a trailing Z or offset is converted to Istanbul time. */
    static LocalDateTime parseValidUntil(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException ignored) {
            // maybe it carries an offset
        }
        try {
            return java.time.OffsetDateTime.parse(s).atZoneSameInstant(ISTANBUL).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw invalid("validUntil yyyy-MM-ddTHH:mm:ss biçiminde olmalı.");
        }
    }

    private static ApiException invalid(String msg) {
        return ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, msg);
    }
}
