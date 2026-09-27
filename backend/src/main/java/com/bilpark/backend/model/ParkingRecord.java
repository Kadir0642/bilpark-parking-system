package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "parking_records")
public class ParkingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String licensePlate;

    @Enumerated(EnumType.STRING)
    private StreetLocation street;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    // Snapshotting Pattern: location info stored at archive time for reporting
    private String region;
    private String neighborhood;

    private LocalDateTime entryTime;
    private LocalDateTime exitTime;

    @Enumerated(EnumType.STRING)
    private VehicleType vehicleType;

    private Double fee;

    @Enumerated(EnumType.STRING)
    private ParkingStatus status;

    private boolean debtPaid = true; // By default paid unless it's a RUNAWAY

    public ParkingRecord(String licensePlate, StreetLocation street, Zone zone,
                         String region, String neighborhood, LocalDateTime entryTime) {
        this.licensePlate = licensePlate;
        this.street = street;
        this.zone = zone;
        this.region = region;
        this.neighborhood = neighborhood;
        this.entryTime = entryTime;
    }
}
