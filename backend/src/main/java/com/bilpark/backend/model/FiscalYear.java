package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Represents a fiscal year / accounting period for the parking system.
 * Used for organizing records and generating yearly Excel exports.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "fiscal_years")
public class FiscalYear {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private String label; // e.g., "2026 Dönemi"

    @Column(nullable = false)
    private boolean isActive = false;

    public FiscalYear(LocalDate startDate, LocalDate endDate, String label) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.label = label;
        this.isActive = false; // Typically only one is active at a time
    }
}
