package com.bilpark.backend.service;

import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.ZoneRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ZoneService {

    private final ZoneRepository zoneRepository;

    public ZoneService(ZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    /** Returns all zones ordered by street then zone number. */
    public List<Zone> getAllZones() {
        return zoneRepository.findAllByOrderByStreetAscZoneNumberAsc();
    }

    /** Returns all zones for a specific street. */
    public List<Zone> getZonesByStreet(StreetLocation street) {
        return zoneRepository.findByStreetOrderByZoneNumber(street);
    }

    /** Returns a single zone by ID. Throws if not found. */
    public Zone getZoneById(Long id) {
        return zoneRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Zone not found with id: " + id));
    }

    /**
     * Updates the landmark names of a zone.
     * Only admins should call this endpoint.
     */
    public Zone updateLandmarks(Long zoneId, String leftLandmark, String rightLandmark) {
        Zone zone = getZoneById(zoneId);
        zone.setLeftLandmark(leftLandmark);
        zone.setRightLandmark(rightLandmark);
        return zoneRepository.save(zone);
    }

    /**
     * Updates the display name of a zone.
     */
    public Zone updateZoneName(Long zoneId, String zoneName) {
        Zone zone = getZoneById(zoneId);
        zone.setZoneName(zoneName);
        return zoneRepository.save(zone);
    }
}
