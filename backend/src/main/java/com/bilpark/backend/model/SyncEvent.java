package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Change log row for live sync. The id is the sync version ("since") clients resume from.
 * payload is the event body as JSON text.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "sync_events")
public class SyncEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SyncEventType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SyncScope scope;

    @Column(name = "zone_id")
    private Long zoneId;

    @Column(length = 50)
    private String street; // StreetLocation name, for spots without a zone

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
