package com.bilpark.backend.service;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Per (user, device) officer shifts: opened at login, closed at logout. */
@Service
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final Clock clock;

    public ShiftService(ShiftRepository shiftRepository, Clock clock) {
        this.shiftRepository = shiftRepository;
        this.clock = clock;
    }

    /** Opens (or reuses) the officer's shift on this device. Returns null for non-officers. */
    @Transactional
    public Shift openShift(User user, String deviceId) {
        if (user == null || user.getRole() != Role.OFFICER) return null;
        LocalDateTime now = LocalDateTime.now(clock);

        Optional<Shift> open = shiftRepository
                .findFirstByUserAndDeviceIdAndEndTimeIsNullOrderByStartTimeDesc(user, deviceId);
        if (open.isPresent()) {
            Shift s = open.get();
            if (s.getStartTime().toLocalDate().equals(LocalDate.now(clock))) {
                return s; // aynı gün aynı cihaz: yeniden girişte başlangıç sıfırlanmaz
            }
            // önceki günden kalmış: sistem kapatır
            s.setEndTime(now);
            s.setAutoClosed(true);
            shiftRepository.save(s);
        }
        return shiftRepository.save(new Shift(user, user.getAssignedZone(), deviceId, now));
    }

    /** Closes this user's open shift on this device only; no-op when none. */
    @Transactional
    public void closeShift(User user, String deviceId) {
        if (user == null) return;
        shiftRepository.findFirstByUserAndDeviceIdAndEndTimeIsNullOrderByStartTimeDesc(user, deviceId)
                .ifPresent(s -> {
                    s.setEndTime(LocalDateTime.now(clock));
                    shiftRepository.save(s);
                });
    }

    /** Closes every open shift of the user (all devices), e.g. when the account is deactivated. */
    @Transactional
    public void closeAllOpenShifts(User user) {
        if (user == null) return;
        LocalDateTime now = LocalDateTime.now(clock);
        for (Shift s : shiftRepository.findByUserAndEndTimeIsNull(user)) {
            s.setEndTime(now);
            shiftRepository.save(s);
        }
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
