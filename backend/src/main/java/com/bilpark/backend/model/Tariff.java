package com.bilpark.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One version of the parking tariff. The id is the tariff version stored on parking records.
 * At most one ACTIVE and one SCHEDULED row exist (partial unique indexes, Flyway V3).
 * paidEnd = 00:00 means midnight (end of day).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "tariffs")
public class Tariff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TariffStatus status = TariffStatus.DRAFT;

    // EAGER on purpose: Tariff instances are cached in memory across transactions
    @OneToMany(mappedBy = "tariff", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("vehicleType")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<TariffVehiclePrice> prices = new ArrayList<>();

    @Column(name = "free_minutes", nullable = false)
    private Integer freeMinutes;

    @Column(name = "paid_start", nullable = false)
    private LocalTime paidStart;

    @Column(name = "paid_end", nullable = false)
    private LocalTime paidEnd;

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(length = 500)
    private String note;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "created_by_username", nullable = false)
    private String createdByUsername; // snapshot, safe to serialize

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Price row of a type, or null when the type has no row in this version. */
    public TariffVehiclePrice priceOf(String code) {
        for (TariffVehiclePrice p : prices) {
            if (p.getVehicleType().equals(code)) return p;
        }
        return null;
    }
}
