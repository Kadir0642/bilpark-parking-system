package com.bilpark.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

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

    @Column(name = "small_first_hour", nullable = false)
    private Double smallFirstHour;

    @Column(name = "small_extra_hour", nullable = false)
    private Double smallExtraHour;

    @Column(name = "large_first_hour", nullable = false)
    private Double largeFirstHour;

    @Column(name = "large_extra_hour", nullable = false)
    private Double largeExtraHour;

    @Column(name = "free_minutes", nullable = false)
    private Integer freeMinutes;

    @Column(name = "paid_start", nullable = false)
    private LocalTime paidStart;

    @Column(name = "paid_end", nullable = false)
    private LocalTime paidEnd;

    @Column(name = "subscription_small_monthly", nullable = false)
    private Double subscriptionSmallMonthly;

    @Column(name = "subscription_large_monthly", nullable = false)
    private Double subscriptionLargeMonthly;

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
}
