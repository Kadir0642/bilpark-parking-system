package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One audited action: who, when, from which device, what changed (before/after JSON summaries).
 * The application never edits rows, except the 5-year retention job which clears the
 * before/after details (plates) of very old rows.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_username", nullable = false)
    private String actorUsername; // "system" for scheduled jobs

    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditAction action;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(length = 500)
    private String summary;

    @Column(name = "before_data", columnDefinition = "text")
    private String beforeData;

    @Column(name = "after_data", columnDefinition = "text")
    private String afterData;
}
