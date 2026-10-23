package com.bilpark.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    // Null for RUNAWAY records and for records created before stage 2
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    // User who closed the record (collected the fee or flagged the runaway)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collected_by_id")
    private User collectedBy;

    @Column(name = "collected_by_username")
    private String collectedByUsername; // snapshot, safe to serialize

    @Column(name = "device_id", length = 64)
    private String deviceId; // X-Device-Id of the device that closed the record

    // Tariff version (tariffs.id) that priced this record; null for records before stage 3
    @Column(name = "tariff_id")
    private Long tariffId;

    // Billable minutes inside the paid hours (entry to exit); null for records before stage 3
    @Column(name = "paid_minutes")
    private Integer paidMinutes;

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
