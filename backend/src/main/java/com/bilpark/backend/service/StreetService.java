package com.bilpark.backend.service;

import com.bilpark.backend.model.Street;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.StreetRepository;
import com.bilpark.backend.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StreetService {

    private final StreetRepository streetRepository;
    private final ZoneRepository zoneRepository;

    public record ZoneDto(Long id, Integer zoneNumber, String zoneName, String leftLandmark, String rightLandmark) {}

    public record StreetDto(Long id, String code, String displayName, Integer sortOrder, List<ZoneDto> zones) {}

    @Transactional(readOnly = true)
    public List<StreetDto> listStreets() {
        Map<String, List<ZoneDto>> byStreet = new HashMap<>();
        zoneRepository.findAll().stream()
                .sorted(Comparator.comparing(Zone::getZoneNumber))
                .forEach(z -> byStreet.computeIfAbsent(z.getStreet().name(), k -> new ArrayList<>())
                        .add(new ZoneDto(z.getId(), z.getZoneNumber(), z.getZoneName(),
                                z.getLeftLandmark(), z.getRightLandmark())));
        return streetRepository.findAllByOrderBySortOrderAsc().stream()
                .map(s -> toDto(s, byStreet.getOrDefault(s.getCode(), List.of())))
                .toList();
    }

    private static StreetDto toDto(Street s, List<ZoneDto> zones) {
        return new StreetDto(s.getId(), s.getCode(), s.getDisplayName(), s.getSortOrder(), zones);
    }
}
