package com.bilpark.backend.repository;

import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.Zone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ZoneRepository extends JpaRepository<Zone, Long> {

    // All zones for a given street (ordered by zone number)
    List<Zone> findByStreetOrderByZoneNumber(StreetLocation street);

    // All zones (for admin listing)
    List<Zone> findAllByOrderByStreetAscZoneNumberAsc();

    // Find a specific zone by street + number
    Optional<Zone> findByStreetAndZoneNumber(StreetLocation street, int zoneNumber);
}
