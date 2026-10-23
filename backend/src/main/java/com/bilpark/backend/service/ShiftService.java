package com.bilpark.backend.service;

import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Per (user, device) officer shifts: opened at login, closed at logout. */
@Service
public class ShiftService {

    private final ShiftRepository shiftRepository;

    public ShiftService(ShiftRepository shiftRepository) {
        this.shiftRepository = shiftRepository;
    }

    /** Opens (or reuses) the officer's shift on this device. Returns null for non-officers. */
    @Transactional
    public Shift openShift(User user, String deviceId) {
        if (user == null || user.getRole() != Role.OFFICER) return null;
        LocalDateTime now = LocalDateTime.now();

        Optional<Shift> open = shiftRepository
                .findFirstByUserAndDeviceIdAndEndTimeIsNullOrderByStartTimeDesc(user, deviceId);
        if (open.isPresent()) {
            Shift s = open.get();
            if (s.getStartTime().toLocalDate().equals(LocalDate.now())) {
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
                    s.setEndTime(LocalDateTime.now());
                    shiftRepository.save(s);
                });
    }

    @Transactional(readOnly = true)
    public List<Shift> listOpenShifts() {
        return shiftRepository.findByEndTimeIsNullOrderByStartTimeAsc();
    }
}
