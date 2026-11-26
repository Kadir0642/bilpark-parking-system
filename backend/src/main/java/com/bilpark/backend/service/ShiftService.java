package com.bilpark.backend.service;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.ShiftRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Officer shifts: opened at login, closed at logout. One officer has at most one open shift:
 * logging in on another device closes the shifts left open on other devices (e.g. after a
 * reinstall, whose new random device id would otherwise orphan the old shift), and a nightly job
 * closes forgotten shifts.
 */
@Service
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final Clock clock;
    private final AuditService auditService;

    /** End of the working day (working hours 08:00-22:00): forgotten shifts are closed at this time. */
    public static final LocalTime WORK_END = LocalTime.of(22, 0);

    public ShiftService(ShiftRepository shiftRepository, Clock clock, AuditService auditService) {
        this.shiftRepository = shiftRepository;
        this.clock = clock;
        this.auditService = auditService;
    }

    /** Opens (or reuses) the officer's shift on this device. Returns null for non-officers. */
    @Transactional
    public Shift openShift(User user, String deviceId) {
        if (user == null || user.getRole() != Role.OFFICER) return null;
        LocalDateTime now = LocalDateTime.now(clock);

        // Tek açık vardiya: bu memurun başka cihazlarda kalmış açık vardiyalarını kapat
        for (Shift other : shiftRepository.findByUserAndEndTimeIsNull(user)) {
            if (deviceId.equals(other.getDeviceId())) continue;
            autoClose(other, closingTimeFor(other, now), "Vardiya otomatik kapandı (başka cihazdan giriş)", deviceId);
        }

        Optional<Shift> open = shiftRepository
                .findFirstByUserAndDeviceIdAndEndTimeIsNullOrderByStartTimeDesc(user, deviceId);
        if (open.isPresent()) {
            Shift s = open.get();
            if (s.getStartTime().toLocalDate().equals(LocalDate.now(clock))) {
                return s; // aynı gün aynı cihaz: yeniden girişte başlangıç sıfırlanmaz
            }
            // önceki günden kalmış: sistem kapatır (süre şişmesin diye o günün sınırında)
            autoClose(s, closingTimeFor(s, now), "Vardiya otomatik kapandı (önceki günden kalmış)", null);
        }
        return shiftRepository.save(new Shift(user, user.getAssignedZone(), deviceId, now));
    }

    /**
     * Nightly clean-up: closes every still-open shift at {@link #closingTimeFor}. Returns how many were closed.
     */
    @Transactional
    public int closeForgottenShifts() {
        LocalDateTime now = LocalDateTime.now(clock);
        int n = 0;
        for (Shift s : shiftRepository.findByEndTimeIsNullOrderByStartTimeAsc()) {
            autoClose(s, closingTimeFor(s, now), "Vardiya otomatik kapandı (gün sonu)", null);
            n++;
        }
        return n;
    }

    /**
     * End time for a system-closed shift, so that forgotten shifts do not inflate worked hours.
     * boundary = 22:00 of the start day for shifts started before 22:00, else 23:59:59 of the start day.
     * Result = min(now, boundary), never before the start time.
     */
    static LocalDateTime closingTimeFor(Shift s, LocalDateTime now) {
        LocalDateTime start = s.getStartTime();
        LocalDateTime workEnd = start.toLocalDate().atTime(WORK_END);
        LocalDateTime boundary = start.isBefore(workEnd) ? workEnd : start.toLocalDate().atTime(LocalTime.of(23, 59, 59));
        LocalDateTime end = now.isBefore(boundary) ? now : boundary;
        return end.isBefore(start) ? start : end;
    }

    private void autoClose(Shift s, LocalDateTime end, String summary, String newDeviceId) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("deviceId", s.getDeviceId());
        before.put("startTime", s.getStartTime().toString());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("endTime", end.toString());
        after.put("autoClosed", true);
        if (newDeviceId != null) after.put("newDeviceId", newDeviceId);
        s.setEndTime(end);
        s.setAutoClosed(true);
        shiftRepository.save(s);
        auditService.logSystem(AuditAction.SHIFT_AUTO_CLOSED, "Shift", s.getId(),
                s.getUsername() + ": " + summary, before, after);
    }

    /** Closes this user's open shift on this device only (capped by {@link #closingTimeFor}, see {@link #closeAt}); no-op when none. */
    @Transactional
    public void closeShift(User user, String deviceId) {
        if (user == null) return;
        LocalDateTime now = LocalDateTime.now(clock);
        shiftRepository.findFirstByUserAndDeviceIdAndEndTimeIsNullOrderByStartTimeDesc(user, deviceId)
                .ifPresent(s -> closeAt(s, now, "Vardiya otomatik kapandı (çıkış gün sınırından sonra)"));
    }

    /** Closes every open shift of the user (all devices, capped by {@link #closingTimeFor}), e.g. when the account is deactivated. */
    @Transactional
    public void closeAllOpenShifts(User user) {
        if (user == null) return;
        LocalDateTime now = LocalDateTime.now(clock);
        for (Shift s : shiftRepository.findByUserAndEndTimeIsNull(user)) {
            closeAt(s, now, "Vardiya otomatik kapandı (hesap değişikliği, gün sınırında)");
        }
    }

    /**
     * Closes a shift for a user action (logout, account change). Before the day's boundary it ends now, as a
     * normal close. When {@link #closingTimeFor} caps the end time (end before now), the system decided the
     * end, so the shift is marked autoClosed and a SHIFT_AUTO_CLOSED audit row is written, like the nightly job.
     */
    private void closeAt(Shift s, LocalDateTime now, String cappedSummary) {
        LocalDateTime end = closingTimeFor(s, now);
        if (end.isBefore(now)) {
            autoClose(s, end, cappedSummary, null);
            return;
        }
        s.setEndTime(end);
        shiftRepository.save(s);
    }

    @Transactional(readOnly = true)
    public List<Shift> listOpenShifts() {
        return shiftRepository.findByEndTimeIsNullOrderByStartTimeAsc();
    }

    /** Admin shift list: shifts that started in [from, to] (inclusive days), newest first. */
    @Transactional(readOnly = true)
    public Page<Shift> search(LocalDate from, LocalDate to, Long zoneId, Long userId, int page, int size) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Bitiş tarihi başlangıçtan önce olamaz.");
        }
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        Specification<Shift> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("startTime"), start));
            ps.add(cb.lessThan(root.<LocalDateTime>get("startTime"), end));
            if (zoneId != null) ps.add(cb.equal(root.get("zone").get("id"), zoneId));
            if (userId != null) ps.add(cb.equal(root.get("user").get("id"), userId));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        int sz = Math.max(1, Math.min(size, 200));
        return shiftRepository.findAll(spec, PageRequest.of(Math.max(0, page), sz,
                Sort.by(Sort.Direction.DESC, "startTime").and(Sort.by(Sort.Direction.DESC, "id"))));
    }
}
