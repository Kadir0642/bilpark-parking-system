package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Monthly parking subscription.
 * The monthly fee comes from the vehicle type's row in the current tariff.
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

    // Not unique: a plate keeps its old (EXPIRED/CANCELLED) rows when it renews.
    // "One ACTIVE subscription per plate" is enforced in SubscriptionService
    // and by the partial unique index ux_subscriptions_active_plate (Flyway V2).
    @Column(nullable = false)
    private String licensePlate; // Normalized by PlateNormalizer

    @Column(nullable = false, length = 30)
    private String vehicleType; // vehicle_types.code

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate; // Typically startDate + 1 month

    private Double monthlyFee; // from the tariff row of the vehicle type

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    private String ownerName;  // Optional: vehicle owner name
    private String ownerPhone; // Optional: contact number

    public Subscription(String licensePlate, String vehicleType,
                        LocalDate startDate, String ownerName, String ownerPhone) {
        this.licensePlate = licensePlate;
        this.vehicleType  = vehicleType;
        this.startDate    = startDate;
        this.endDate      = startDate.plusMonths(1);
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
