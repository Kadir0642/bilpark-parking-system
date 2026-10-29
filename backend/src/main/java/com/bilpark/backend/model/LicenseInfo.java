package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The license, a single row (id = 1), visible to the admin. After expiresOn + graceDays the system
 * goes read-only (no new entries / exits / tariff or user changes); data is never deleted.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "license_info")
public class LicenseInfo {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(nullable = false)
    private String licensee;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn; // last day of the license (inclusive)

    @Column(name = "grace_days", nullable = false)
    private Integer graceDays; // contractual grace period after expiresOn

    @Column(length = 500)
    private String note;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;
}
