package com.bilpark.backend.controller;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.DeviceIds;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.service.ParkingService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Record / entry cancellation (soft delete) and the cancelled-records list. */
@RestController
public class RecordController {

    /** Body of both cancel endpoints. */
    public record CancelRequest(String reason) {}

    private final ParkingService parkingService;
    private final UserRepository userRepository;

    public RecordController(ParkingService parkingService, UserRepository userRepository) {
        this.parkingService = parkingService;
        this.userRepository = userRepository;
    }

    private User currentUser(Principal principal) {
        if (principal == null) throw new AccessDeniedException("Oturum bulunamadı.");
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new AccessDeniedException("Kullanıcı bulunamadı."));
    }

    // POST /api/admin/records/{id}/cancel — ADMIN: cancels a PAID / RUNAWAY record
    @PostMapping("/api/admin/records/{id}/cancel")
    public Map<String, Object> cancelRecord(@PathVariable Long id,
                                            @RequestBody(required = false) CancelRequest body,
                                            Principal principal) {
        return parkingService.cancelRecord(id, body != null ? body.reason() : null, currentUser(principal));
    }

    // POST /api/parking/spots/cancel?plate=34ABC123 — officer (own zone) or admin: cancels a wrong active entry
    @PostMapping("/api/parking/spots/cancel")
    public Map<String, Object> cancelSpot(@RequestParam String plate,
                                          @RequestBody(required = false) CancelRequest body,
                                          @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
                                          Principal principal) {
        return parkingService.cancelActiveEntry(plate, body != null ? body.reason() : null,
                currentUser(principal), DeviceIds.sanitize(deviceHeader));
    }

    // GET /api/admin/records/cancelled?from&to&page&size — ADMIN
    @GetMapping("/api/admin/records/cancelled")
    public Map<String, Object> cancelled(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        LocalDateTime start = from == null || from.isBlank()
                ? LocalDateTime.of(2000, 1, 1, 0, 0) : parse(from, "from").atStartOfDay();
        LocalDateTime end = to == null || to.isBlank()
                ? LocalDateTime.of(2100, 1, 1, 0, 0) : parse(to, "to").plusDays(1).atStartOfDay();
        int s = Math.max(1, Math.min(size, 200));
        int p = Math.max(0, page);
        Page<Map<String, Object>> result = parkingService.listCancelled(start, end, p, s);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.getContent());
        body.put("page", p);
        body.put("size", s);
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return body;
    }

    private static LocalDate parse(String s, String name) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "Geçersiz tarih (" + name + "): yyyy-MM-dd biçiminde olmalıdır.");
        }
    }
}
