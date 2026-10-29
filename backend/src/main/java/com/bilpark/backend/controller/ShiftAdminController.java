package com.bilpark.backend.controller;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import com.bilpark.backend.service.ShiftService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin only (SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/shifts")
public class ShiftAdminController {

    private final ShiftService shiftService;
    private final Clock clock;

    public ShiftAdminController(ShiftService shiftService, Clock clock) {
        this.shiftService = shiftService;
        this.clock = clock;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) Long zoneId,
                                    @RequestParam(required = false) Long userId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = parse(from, "from", today.minusDays(6));
        LocalDate t = parse(to, "to", today);
        Page<Shift> result = shiftService.search(f, t, zoneId, userId, page, size);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> content = result.getContent().stream().map(s -> toMap(s, now)).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return body;
    }

    private static LocalDate parse(String v, String name, LocalDate def) {
        if (v == null || v.isBlank()) return def;
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "'" + name + "' tarihi yyyy-MM-dd biçiminde olmalı.");
        }
    }

    private static Map<String, Object> toMap(Shift s, LocalDateTime now) {
        User u = s.getUser();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("userId", u != null ? u.getId() : null);
        m.put("username", s.getUsername());
        m.put("fullName", u != null ? u.getFullName() : null);
        m.put("zoneId", s.getZone() != null ? s.getZone().getId() : null);
        m.put("zoneName", s.getZone() != null ? s.getZone().getZoneName() : null);
        m.put("street", s.getZone() != null ? s.getZone().getStreet().name() : null);
        m.put("deviceId", s.getDeviceId());
        m.put("startTime", s.getStartTime());
        m.put("endTime", s.getEndTime());
        LocalDateTime end = s.getEndTime() != null ? s.getEndTime() : now;
        m.put("durationMinutes", Math.max(0, Duration.between(s.getStartTime(), end).toMinutes()));
        m.put("open", s.isOpen());
        m.put("autoClosed", s.isAutoClosed());
        return m;
    }
}
