package com.bilpark.backend.controller;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.User;
import com.bilpark.backend.service.UserAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin only (SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    public record ActiveRequest(Boolean active) {}

    public record CreateRequest(String username, String fullName, String password, String role, Long zoneId) {}

    public record ZoneRequest(Long zoneId) {}

    public record ResetPasswordRequest(String newPassword) {}

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return userAdminService.listUsers().stream().map(UserAdminController::toMap).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateRequest r) {
        if (r == null) throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "İstek gövdesi gerekli.");
        return toMap(userAdminService.create(r.username(), r.fullName(), r.password(), r.role(), r.zoneId()));
    }

    /** Partial update: only the keys present in the body change ("zoneId": null clears the zone). */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body, Principal principal) {
        String fullName = body.get("fullName") instanceof String s ? s : null;
        String role = body.get("role") instanceof String s ? s : null;
        boolean zoneGiven = body.containsKey("zoneId");
        Long zoneId = null;
        if (body.get("zoneId") instanceof Number n) zoneId = n.longValue();
        else if (zoneGiven && body.get("zoneId") != null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "zoneId sayı olmalı.");
        }
        return toMap(userAdminService.update(id, fullName, role, zoneId, zoneGiven, principal.getName()));
    }

    @PutMapping("/{id}/zone")
    public Map<String, Object> assignZone(@PathVariable Long id, @RequestBody ZoneRequest request) {
        if (request == null || request.zoneId() == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "zoneId alanı gerekli.");
        }
        return toMap(userAdminService.assignZone(id, request.zoneId()));
    }

    @PutMapping("/{id}/active")
    public Map<String, Object> setActive(@PathVariable Long id, @RequestBody ActiveRequest request, Principal principal) {
        if (request == null || request.active() == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "active alanı gerekli.");
        }
        return toMap(userAdminService.setActive(id, request.active(), principal.getName()));
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest request) {
        if (request == null || request.newPassword() == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "newPassword alanı gerekli.");
        }
        User u = userAdminService.resetPassword(id, request.newPassword());
        Map<String, Object> m = toMap(u);
        m.put("message", "Şifre sıfırlandı. Kullanıcının açık oturumları kapandı.");
        return m;
    }

    private static Map<String, Object> toMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("fullName", u.getFullName());
        m.put("role", u.getRole().name());
        m.put("active", u.isActive());
        m.put("zoneId", u.getAssignedZone() != null ? u.getAssignedZone().getId() : null);
        m.put("zoneName", u.getAssignedZone() != null ? u.getAssignedZone().getZoneName() : null);
        m.put("streetCode", u.getAssignedZone() != null ? u.getAssignedZone().getStreet().name() : null);
        m.put("lastLoginTime", u.getLastLoginTime());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }
}
