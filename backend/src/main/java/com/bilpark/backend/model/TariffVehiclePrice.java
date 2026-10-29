package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;

/** Price of one vehicle type in one tariff version. A missing row means the type is off in that version. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "tariff_vehicle_prices")
@IdClass(TariffVehiclePrice.Key.class)
public class TariffVehiclePrice {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tariff_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Tariff tariff;

    @Id
    @Column(name = "vehicle_type", length = 30)
    private String vehicleType;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "first_hour")
    private Double firstHour;

    @Column(name = "extra_hour")
    private Double extraHour;

    @Column(name = "subscription_monthly")
    private Double subscriptionMonthly;

    public TariffVehiclePrice(Tariff tariff, String vehicleType, boolean enabled,
                              Double firstHour, Double extraHour, Double subscriptionMonthly) {
        this.tariff = tariff;
        this.vehicleType = vehicleType;
        this.enabled = enabled;
        this.firstHour = firstHour;
        this.extraHour = extraHour;
        this.subscriptionMonthly = subscriptionMonthly;
    }

    /** Composite key; "tariff" holds the tariff id (derived-identity style IdClass). */
    @Data
    @NoArgsConstructor
    public static class Key implements Serializable {
        private Long tariff;
        private String vehicleType;
    }
}
