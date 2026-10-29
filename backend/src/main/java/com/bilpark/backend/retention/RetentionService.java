package com.bilpark.backend.retention;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.repository.AdminNotificationRepository;
import com.bilpark.backend.settings.SettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data retention. Business and financial records are never deleted:
 *   - records, subscriptions and audit details older than 5 years lose their personal data (plate -> ANONIM),
 *   - subscriber name/phone are cleared 1 year after the subscription ended,
 *   - admin notifications and shift (mesai) rows older than 1 year are deleted (the only deletions; scores are computed from shifts on the fly).
 * Idempotent: a second run on the same day changes nothing. active_park_spots is never touched.
 */
@Service
public class RetentionService {

    public static final String ANON_PLATE = "ANONIM";
    public static final String AUDIT_ANON_SUMMARY = "Saklama süresi doldu; ayrıntılar anonimleştirildi.";

    public record RetentionResult(LocalDate today, int recordPlatesAnonymized, int subscriptionPlatesAnonymized,
                                  int auditLogsAnonymized, int subscriberDataCleared, int shiftsDeleted,
                                  int notificationsDeleted) {
        public int total() {
            return recordPlatesAnonymized + subscriptionPlatesAnonymized + auditLogsAnonymized
                    + subscriberDataCleared + shiftsDeleted + notificationsDeleted;
        }
    }

    @PersistenceContext
    private EntityManager em;

    private final AdminNotificationRepository notificationRepository;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final JsonMapper mapper;
    private final Clock clock;

    public RetentionService(AdminNotificationRepository notificationRepository, SettingsService settingsService, AuditService auditService, JsonMapper mapper, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public RetentionResult run(LocalDate today) {
        LocalDateTime fiveYears = today.minusYears(5).atStartOfDay();
        LocalDate fiveYearsDate = today.minusYears(5);
        LocalDate oneYearDate = today.minusYears(1);
        LocalDateTime oneYear = oneYearDate.atStartOfDay();

        int records = em.createQuery("UPDATE ParkingRecord r SET r.licensePlate = :anon, r.plateAnonymized = true "
                        + "WHERE r.plateAnonymized = false AND ((r.exitTime IS NOT NULL AND r.exitTime < :c) "
                        + "OR (r.exitTime IS NULL AND r.entryTime < :c))")
                .setParameter("anon", ANON_PLATE).setParameter("c", fiveYears).executeUpdate();

        int subs = em.createQuery("UPDATE Subscription s SET s.licensePlate = :anon WHERE s.endDate < :d "
                        + "AND s.licensePlate <> :anon "
                        + "AND s.status <> com.bilpark.backend.model.SubscriptionStatus.ACTIVE")
                .setParameter("anon", ANON_PLATE).setParameter("d", fiveYearsDate).executeUpdate();

        int audits = em.createQuery("UPDATE AuditLog a SET a.beforeData = null, a.afterData = null, a.summary = :s "
                        + "WHERE a.createdAt < :c AND (a.beforeData IS NOT NULL OR a.afterData IS NOT NULL)")
                .setParameter("s", AUDIT_ANON_SUMMARY).setParameter("c", fiveYears).executeUpdate();

        int subscribers = em.createQuery("UPDATE Subscription s SET s.ownerName = null, s.ownerPhone = null "
                        + "WHERE s.endDate < :d AND (s.ownerName IS NOT NULL OR s.ownerPhone IS NOT NULL)")
                .setParameter("d", oneYearDate).executeUpdate();

        int shifts = em.createQuery("DELETE FROM Shift s WHERE s.startTime < :c AND s.endTime IS NOT NULL")
                .setParameter("c", oneYear).executeUpdate();

        notificationRepository.deleteReadsOlderThan(oneYear);
        int notifications = notificationRepository.deleteOlderThan(oneYear);

        RetentionResult result = new RetentionResult(today, records, subs, audits, subscribers, shifts, notifications);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ranAt", LocalDateTime.now(clock).toString());
        summary.put("today", today.toString());
        summary.put("recordPlatesAnonymized", records);
        summary.put("subscriptionPlatesAnonymized", subs);
        summary.put("auditLogsAnonymized", audits);
        summary.put("subscriberDataCleared", subscribers);
        summary.put("shiftsDeleted", shifts);
        summary.put("notificationsDeleted", notifications);
        settingsService.put(SettingsService.KEY_RETENTION_LAST_RUN, mapper.writeValueAsString(summary));

        if (result.total() > 0) {
            auditService.logSystem(AuditAction.RETENTION_RUN, "Retention", today.toString(),
                    "Saklama süresi işi: " + records + " kayıt plakası, " + subs + " abonelik plakası, " + audits
                            + " denetim kaydı anonimleştirildi; " + subscribers + " abone bilgisi temizlendi; "
                            + shifts + " eski mesai kaydı, " + notifications + " eski bildirim silindi.", null, summary);
        }
        return result;
    }
}
