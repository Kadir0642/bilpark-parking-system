package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a working zone (section) within a street.
 * Each zone is assigned to exactly one field officer.
 *
 * Example: Tevfik Bey Caddesi has 3 zones (3 officers),
 *          Ali Riza Ozkay has 2 zones, Cumhuriyet has 1 zone.
 *
 * Landmarks help officers orient themselves on the street
 * (e.g. left side = "Öncü Döner", right side = "Ziraat Bankası").
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "zones",
        uniqueConstraints = @UniqueConstraint(columnNames = {"street", "zone_number"}))
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StreetLocation street;

    @Column(name = "zone_number", nullable = false)
    private Integer zoneNumber; // 1, 2, 3 within the same street

    @Column(nullable = false)
    private String zoneName; // e.g. "Tevfik Bey - Bölüm 1"

    // Memorable real-world landmarks for left/right curb identification
    private String leftLandmark;  // e.g. "Öncü Döner"
    private String rightLandmark; // e.g. "Ziraat Bankası"

    public Zone(StreetLocation street, int zoneNumber, String zoneName,
                String leftLandmark, String rightLandmark) {
        this.street = street;
        this.zoneNumber = zoneNumber;
        this.zoneName = zoneName;
        this.leftLandmark = leftLandmark;
        this.rightLandmark = rightLandmark;
    }
}
