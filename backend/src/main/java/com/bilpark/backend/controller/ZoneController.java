package com.bilpark.backend.controller;

import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.service.ZoneService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for Zone (working section) management.
 *
 * GET  /api/zones                     -> All zones (officer + admin, authenticated)
 * GET  /api/zones/by-street           -> Filter by street (officer + admin)
 * PUT  /api/zones/{id}/landmarks      -> Update landmark names (admin only)
 * PUT  /api/zones/{id}/name           -> Update zone display name (admin only)
 */
@RestController
@RequestMapping("/api/zones")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    // GET /api/zones — All zones (for officer login screen: show zones to pick from)
    @GetMapping
    public List<Zone> getAllZones() {
        return zoneService.getAllZones();
    }

    // GET /api/zones/by-street?street=TEVFIK_BEY
    @GetMapping("/by-street")
    public List<Zone> getZonesByStreet(@RequestParam StreetLocation street) {
        return zoneService.getZonesByStreet(street);
    }

    // PUT /api/zones/{id}/landmarks — Update landmark names (ADMIN only)
    @PutMapping("/{id}/landmarks")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Zone> updateLandmarks(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String left  = body.getOrDefault("leftLandmark", "");
        String right = body.getOrDefault("rightLandmark", "");
        Zone updated = zoneService.updateLandmarks(id, left, right);
        return ResponseEntity.ok(updated);
    }

    // PUT /api/zones/{id}/name — Update zone display name (ADMIN only)
    @PutMapping("/{id}/name")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Zone> updateZoneName(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String name = body.getOrDefault("zoneName", "");
        Zone updated = zoneService.updateZoneName(id, name);
        return ResponseEntity.ok(updated);
    }
}
