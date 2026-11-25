package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** A user has read a notification. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "admin_notification_reads")
@IdClass(AdminNotificationRead.Key.class)
public class AdminNotificationRead {

    @Data
    @NoArgsConstructor
    public static class Key implements Serializable {
        private Long notificationId;
        private Long userId;
    }

    @Id
    @Column(name = "notification_id")
    private Long notificationId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
