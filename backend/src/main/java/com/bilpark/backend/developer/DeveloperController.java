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
    private final DeveloperAuditService auditService;
    private final DeveloperActivityService activityService;
    private final AnnouncementService announcementService;

    public DeveloperController(DeveloperService developerService, DeveloperAuditService auditService,
                               DeveloperActivityService activityService, AnnouncementService announcementService) {
        this.announcementService = announcementService;
        this.auditService = auditService;
        this.activityService = activityService;
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


    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String action,
                                     @RequestParam(required = false) String actor,
                                     @RequestParam(required = false) String actorRole,
                                     @RequestParam(required = false) String entityType,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return auditService.list(new DeveloperAuditService.Query(from, to, action, actor, actorRole, entityType, q),
                page, size);
    }

    @GetMapping("/audit/actions")
    public List<Map<String, String>> auditActions() {
        return auditService.actions();
    }

    @GetMapping("/activity")
    public Map<String, Object> activity(@RequestParam(defaultValue = "24") int hours) {
        if (hours < 1 || hours > DeveloperActivityService.MAX_HOURS) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "hours 1 ile " + DeveloperActivityService.MAX_HOURS + " arasında olmalı.");
        }
        return activityService.activity(hours);
    }

    /** 201; the body is the created notification. */
    @PostMapping("/announcements")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String, Object> createAnnouncement(@RequestBody(required = false) AnnouncementService.Request r,
                                                  java.security.Principal principal) {
        return announcementService.create(r, principal.getName());
    }

    /** Plain array, newest first, each item with readCount. */
    @GetMapping("/announcements")
    public List<Map<String, Object>> announcements(@RequestParam(defaultValue = "30") int limit) {
        return announcementService.list(limit);
    }

    @PostMapping("/admin-password-reset")
    public Map<String, Object> resetAdminPassword(@RequestBody AdminPasswordResetRequest r) {
        if (r == null) throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "İstek gövdesi gerekli.");
        return developerService.resetAdminPassword(r.username(), r.newPassword());
    }
}
