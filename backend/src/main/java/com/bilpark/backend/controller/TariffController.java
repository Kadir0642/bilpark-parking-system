package com.bilpark.backend.controller;

import com.bilpark.backend.dto.TariffRequest;
import com.bilpark.backend.dto.TariffScheduleRequest;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.service.TariffService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Tariff endpoints. /api/tariff/current is public; /api/admin/** is ADMIN-only (SecurityConfig).
 */
@RestController
@CrossOrigin("*")
public class TariffController {

    private final TariffService tariffService;
    private final UserRepository userRepository;

    public TariffController(TariffService tariffService, UserRepository userRepository) {
        this.tariffService = tariffService;
        this.userRepository = userRepository;
    }

    private User currentUser(Principal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Oturum bulunamadı.");
        }
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new AccessDeniedException("Kullanıcı bulunamadı."));
    }

    // GET /api/tariff/current — public; current tariff + "upcoming" (scheduled tariff or null)
    @GetMapping("/api/tariff/current")
    public Map<String, Object> current() {
        return tariffService.currentWithUpcoming();
    }

    // GET /api/admin/tariffs
    @GetMapping("/api/admin/tariffs")
    public List<Map<String, Object>> list() {
        return tariffService.list();
    }

    // POST /api/admin/tariffs — create a draft
    @PostMapping("/api/admin/tariffs")
    public ResponseEntity<Map<String, Object>> create(@RequestBody TariffRequest req, Principal principal) {
        return ResponseEntity.ok(tariffService.createDraft(req, currentUser(principal)));
    }

    // PUT /api/admin/tariffs/{id} — edit a draft
    @PutMapping("/api/admin/tariffs/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody TariffRequest req) {
        return tariffService.updateDraft(id, req);
    }

    // POST /api/admin/tariffs/{id}/preview
    @PostMapping("/api/admin/tariffs/{id}/preview")
    public Map<String, Object> preview(@PathVariable Long id) {
        return tariffService.preview(id);
    }

    // POST /api/admin/tariffs/{id}/schedule  {"effectiveFrom":"2026-11-01T00:00:00","password":"..."}
    @PostMapping("/api/admin/tariffs/{id}/schedule")
    public Map<String, Object> schedule(@PathVariable Long id, @RequestBody TariffScheduleRequest req,
                                        Principal principal) {
        return tariffService.schedule(id, req.getEffectiveFrom(), req.getPassword(), currentUser(principal));
    }

    // POST /api/admin/tariffs/{id}/cancel
    @PostMapping("/api/admin/tariffs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        return tariffService.cancel(id);
    }
}
