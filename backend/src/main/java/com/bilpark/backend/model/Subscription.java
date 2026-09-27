package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Monthly parking subscription.
 * Small vehicles: 2,500 TL/month
 * Large vehicles: 3,000 TL/month
 *
 * When a subscribed vehicle is scanned at check-in, the officer
 * receives a notification and the vehicle is NOT recorded as a
 * regular park spot (no fee will be charged at exit).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String licensePlate; // Normalized to uppercase

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleType vehicleType; // SMALL = 2500 TL, LARGE = 3000 TL

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate; // Typically startDate + 1 month

    private Double monthlyFee; // 2500.0 or 3000.0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    private String ownerName;  // Optional: vehicle owner name
    private String ownerPhone; // Optional: contact number

    public Subscription(String licensePlate, VehicleType vehicleType,
                        LocalDate startDate, String ownerName, String ownerPhone) {
        this.licensePlate = licensePlate.toUpperCase();
        this.vehicleType  = vehicleType;
        this.startDate    = startDate;
        this.endDate      = startDate.plusMonths(1);
        this.monthlyFee   = (vehicleType == VehicleType.LARGE) ? 3000.0 : 2500.0;
        this.status       = SubscriptionStatus.ACTIVE;
        this.ownerName    = ownerName;
        this.ownerPhone   = ownerPhone;
    }

    /** Returns true if this subscription is currently valid. */
    public boolean isCurrentlyActive() {
        LocalDate today = LocalDate.now();
        return status == SubscriptionStatus.ACTIVE
                && !today.isBefore(startDate)
                && !today.isAfter(endDate);
    }
}
