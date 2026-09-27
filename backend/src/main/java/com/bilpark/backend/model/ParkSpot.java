package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data               // Lombok: Getter, Setter, toString, equals, hashCode otomatik üretir
@NoArgsConstructor  // Lombok: JPA için parametresiz constructor
@Entity
@Table(name = "active_park_spots")
public class ParkSpot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version; // Optimistic locking for concurrency

    @Column(unique = true, nullable = false)
    private String currentPlate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StreetLocation street;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    private String region = "Merkez";
    private String neighborhood;
    private String side;

    private LocalDateTime entryTime;

    @Enumerated(EnumType.STRING)
    private VehicleType currentType;

    @Enumerated(EnumType.STRING)
    private ParkingStatus status = ParkingStatus.ACTIVE;

    @Transient
    private Double accumulatedDebt; // Used to show unpaid runaway debts on check-in

    @Transient
    private Boolean hasSubscription; // Used to show if vehicle has active subscription on check-in

    public ParkSpot(String currentPlate, StreetLocation street, VehicleType currentType,
                    String region, String neighborhood, String side, Zone zone) {
        this.currentPlate = currentPlate;
        this.street = street;
        this.currentType = currentType;
        this.region = region;
        this.neighborhood = neighborhood;
        this.side = side;
        this.zone = zone;
        this.entryTime = LocalDateTime.now();
        this.status = ParkingStatus.ACTIVE;
    }
}
