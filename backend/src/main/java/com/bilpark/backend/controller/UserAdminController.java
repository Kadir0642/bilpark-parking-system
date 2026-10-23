package com.bilpark.backend.controller;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.User;
import com.bilpark.backend.service.UserAdminService;
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

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return userAdminService.listUsers().stream().map(UserAdminController::toMap).toList();
    }

    @PutMapping("/{id}/active")
    public Map<String, Object> setActive(@PathVariable Long id, @RequestBody ActiveRequest request, Principal principal) {
        if (request == null || request.active() == null) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "active alanı gerekli.");
        }
        return toMap(userAdminService.setActive(id, request.active(), principal.getName()));
    }

    private static Map<String, Object> toMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("role", u.getRole().name());
        m.put("active", u.isActive());
        m.put("zoneId", u.getAssignedZone() != null ? u.getAssignedZone().getId() : null);
        m.put("zoneName", u.getAssignedZone() != null ? u.getAssignedZone().getZoneName() : null);
        m.put("lastLoginTime", u.getLastLoginTime());
        return m;
    }
}
