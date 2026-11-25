package com.bilpark.backend.notification;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/** The admin's notification inbox (ADMIN-only via SecurityConfig: /api/admin/**). Not audited, works in read-only mode. */
@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {

    private final NotificationService service;
    private final UserRepository users;

    public AdminNotificationController(NotificationService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "30") int limit, Principal principal) {
        return service.list(userId(principal), limit);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable Long id, Principal principal) {
        service.markRead(id, userId(principal));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(Principal principal) {
        service.markAllRead(userId(principal));
        return ResponseEntity.noContent().build();
    }

    private Long userId(Principal principal) {
        return users.findByUsername(principal.getName()).orElseThrow(() ->
                ApiException.notFound(ErrorCodes.NOT_FOUND, "Kullanıcı bulunamadı.")).getId();
    }
}
