package com.bilpark.backend.developer;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.license.LicenseService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** DEVELOPER only (SecurityConfig: /api/developer/**). Maintenance powers written in the contract annex. */
@RestController
@RequestMapping("/api/developer")
public class DeveloperController {

    public record LicenseRequest(String expiresOn, Integer graceDays, String licensee, String note) {}

    public record AdminPasswordResetRequest(String username, String newPassword) {}

    private final DeveloperService developerService;

    public DeveloperController(DeveloperService developerService) {
        this.developerService = developerService;
    }

    @PutMapping("/license")
    public LicenseService.LicenseStatus updateLicense(@RequestBody LicenseRequest r) {
        if (r == null || r.expiresOn() == null || r.expiresOn().isBlank()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "expiresOn alanı gerekli (yyyy-MM-dd).");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(r.expiresOn().trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "expiresOn yyyy-MM-dd biçiminde olmalı.");
        }
        return developerService.updateLicense(date, r.graceDays(), r.licensee(), r.note());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return developerService.health();
    }

    @GetMapping("/errors")
    public List<Map<String, Object>> errors(@RequestParam(defaultValue = "50") int limit) {
        return developerService.errors(limit);
    }

    @PostMapping("/admin-password-reset")
    public Map<String, Object> resetAdminPassword(@RequestBody AdminPasswordResetRequest r) {
        if (r == null) throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "İstek gövdesi gerekli.");
        return developerService.resetAdminPassword(r.username(), r.newPassword());
    }
}
