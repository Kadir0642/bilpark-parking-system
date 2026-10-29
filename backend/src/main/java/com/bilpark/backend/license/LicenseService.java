package com.bilpark.backend.license;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.LicenseInfo;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.repository.LicenseInfoRepository;
import com.bilpark.backend.sync.ChangePublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * License state. Visible to everyone who is logged in; changed only by the developer role.
 *
 *   ACTIVE    today <= expiresOn                  (warnings at 30, 7 and 1 days left)
 *   GRACE     expiresOn < today <= expiresOn+grace (contractual grace period, still writable)
 *   READ_ONLY after that: viewing and Excel stay open; entries, exits, runaway, tariff and user
 *             changes answer 423 (ReadOnlyModeInterceptor). Data is never deleted.
 */
@Service
public class LicenseService {

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_GRACE = "GRACE";
    public static final String STATE_READ_ONLY = "READ_ONLY";

    public static final String READ_ONLY_MESSAGE =
            "Lisans süresi ve ek süre doldu; sistem okunur modda. Kayıtları görüntüleyebilir ve Excel alabilirsiniz, "
                    + "ancak giriş, çıkış, kaçak, tarife ve kullanıcı değişikliği yapılamaz. Lisansı yenilemek için "
                    + "sistem sağlayıcınızla görüşün.";

    private static final long CACHE_MILLIS = 60_000;
    private static final int DEFAULT_GRACE_DAYS = 15;
    private static final int MAX_GRACE_DAYS = 365;

    /** Status body of GET /api/license/status. warningLevel: 30 / 7 / 1 while ACTIVE, 0 after expiry, null otherwise. */
    public record LicenseStatus(String licensee, LocalDate expiresOn, int graceDays, LocalDate graceEndsOn,
                                long daysLeft, long graceDaysLeft, String state, Integer warningLevel,
                                boolean readOnly, String message, LocalDateTime updatedAt, String updatedBy,
                                String note) {}

    private final LicenseInfoRepository repository;
    private final AuditService auditService;
    private final ChangePublisher changePublisher;
    private final Clock clock;

    private volatile Boolean cachedReadOnly;
    private volatile long cachedAt;

    public LicenseService(LicenseInfoRepository repository, AuditService auditService,
                          ChangePublisher changePublisher, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.changePublisher = changePublisher;
        this.clock = clock;
    }

    /** The license row; created with defaults (1 year, 15 grace days) when missing (H2 tests, fresh DB without V4 seed). */
    @Transactional
    public LicenseInfo current() {
        return repository.findById(LicenseInfo.SINGLETON_ID).orElseGet(() -> {
            LicenseInfo l = new LicenseInfo();
            l.setId(LicenseInfo.SINGLETON_ID);
            l.setLicensee("BilPark Müşterisi");
            l.setExpiresOn(LocalDate.now(clock).plusDays(365));
            l.setGraceDays(DEFAULT_GRACE_DAYS);
            l.setNote("Varsayılan lisans");
            l.setUpdatedAt(LocalDateTime.now(clock));
            l.setUpdatedBy(AuditService.SYSTEM);
            return repository.save(l);
        });
    }

    @Transactional
    public LicenseStatus status() {
        return statusOf(current(), LocalDate.now(clock));
    }

    /** Pure state computation for a license and a day (tested directly). */
    public static LicenseStatus statusOf(LicenseInfo l, LocalDate today) {
        int grace = l.getGraceDays() == null ? 0 : l.getGraceDays();
        LocalDate graceEnd = l.getExpiresOn().plusDays(grace);
        long daysLeft = ChronoUnit.DAYS.between(today, l.getExpiresOn());
        long graceLeft = Math.max(0, ChronoUnit.DAYS.between(today, graceEnd));

        String state;
        Integer warning;
        String message;
        if (!today.isAfter(l.getExpiresOn())) {
            state = STATE_ACTIVE;
            if (daysLeft <= 1) warning = 1;
            else if (daysLeft <= 7) warning = 7;
            else if (daysLeft <= 30) warning = 30;
            else warning = null;
            message = warning == null ? "Lisans geçerli."
                    : (daysLeft == 0 ? "Lisans bugün sona eriyor." : "Lisansın bitmesine " + daysLeft + " gün kaldı.")
                      + " Süre dolunca " + grace + " günlük ek süre tanınır, sonra sistem okunur moda geçer.";
        } else if (!today.isAfter(graceEnd)) {
            state = STATE_GRACE;
            warning = 0;
            message = "Lisans süresi doldu; ek süre içindesiniz. " + (graceLeft == 0 ? "Ek süre bugün bitiyor."
                    : "Ek sürenin bitmesine " + graceLeft + " gün kaldı.") + " Sonra sistem okunur moda geçer.";
        } else {
            state = STATE_READ_ONLY;
            warning = 0;
            message = READ_ONLY_MESSAGE;
        }
        return new LicenseStatus(l.getLicensee(), l.getExpiresOn(), grace, graceEnd, daysLeft, graceLeft, state,
                warning, STATE_READ_ONLY.equals(state), message, l.getUpdatedAt(), l.getUpdatedBy(), l.getNote());
    }

    /** Cached for 60 s: checked on every write request. */
    public boolean isReadOnly() {
        Boolean c = cachedReadOnly;
        long now = System.currentTimeMillis();
        if (c != null && now - cachedAt < CACHE_MILLIS) return c;
        boolean ro = status().readOnly();
        cachedReadOnly = ro;
        cachedAt = now;
        return ro;
    }

    public void evictCache() {
        cachedReadOnly = null;
    }

    /** Throws 423 READ_ONLY when the system is read-only (for writes outside HTTP, if any). */
    public void requireWritable() {
        if (isReadOnly()) throw ApiException.locked(ErrorCodes.READ_ONLY, READ_ONLY_MESSAGE);
    }

    /**
     * Developer: sets the license dates. null fields keep their value. Audited (LICENSE_UPDATED, before/after)
     * and announced to every client (LICENSE_UPDATED event).
     */
    @Transactional
    public LicenseStatus update(LocalDate expiresOn, Integer graceDays, String licensee, String note) {
        if (graceDays != null && (graceDays < 0 || graceDays > MAX_GRACE_DAYS)) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Ek süre 0-" + MAX_GRACE_DAYS + " gün olmalı.");
        }
        if (licensee != null && licensee.isBlank()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Lisans sahibi boş olamaz.");
        }
        LicenseInfo l = current();
        Map<String, Object> before = snapshot(l);
        if (expiresOn != null) l.setExpiresOn(expiresOn);
        if (graceDays != null) l.setGraceDays(graceDays);
        if (licensee != null) l.setLicensee(licensee.trim());
        if (note != null) l.setNote(note.length() > 500 ? note.substring(0, 500) : note);
        l.setUpdatedAt(LocalDateTime.now(clock));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        l.setUpdatedBy(auth != null ? auth.getName() : AuditService.SYSTEM);
        repository.save(l);
        evictCache();

        LicenseStatus st = statusOf(l, LocalDate.now(clock));
        auditService.log(AuditAction.LICENSE_UPDATED, "License", l.getId(),
                "Lisans güncellendi: bitiş " + l.getExpiresOn() + ", ek süre " + l.getGraceDays() + " gün",
                before, snapshot(l));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("expiresOn", l.getExpiresOn().toString());
        ev.put("graceDays", l.getGraceDays());
        ev.put("state", st.state());
        changePublisher.globalEvent(SyncEventType.LICENSE_UPDATED, ev);
        return st;
    }

    private static Map<String, Object> snapshot(LicenseInfo l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("licensee", l.getLicensee());
        m.put("expiresOn", l.getExpiresOn() != null ? l.getExpiresOn().toString() : null);
        m.put("graceDays", l.getGraceDays());
        m.put("note", l.getNote());
        return m;
    }
}
