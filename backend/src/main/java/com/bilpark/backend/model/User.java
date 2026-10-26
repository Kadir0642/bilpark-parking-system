package com.bilpark.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * System user (field officer or admin).
 * Each officer is assigned to a specific working zone.
 * Admin has no zone assignment (null = all access).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @JsonIgnore
    @Column(nullable = false)
    private String password; // BCrypt hashed, never serialized

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "zone_id")
    private Zone assignedZone; // null for ADMIN, required for OFFICER

    @Column(name = "last_login_time")
    private java.time.LocalDateTime lastLoginTime;

    // false = passive: cannot log in, existing tokens stop working (Flyway V3)
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "full_name")
    private String fullName; // Ad soyad (Flyway V4), optional

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    // Europe/Istanbul local time (Clock bean). JWTs issued before this second are rejected.
    @JsonIgnore
    @Column(name = "password_changed_at")
    private java.time.LocalDateTime passwordChangedAt;

    public User(String username, String password, Role role, Zone assignedZone) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.assignedZone = assignedZone;
    }
}
