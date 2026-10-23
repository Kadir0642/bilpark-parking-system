package com.bilpark.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One working shift of a field officer on one device.
 * Opened at login, closed at logout. Shifts are per (user, device): a second
 * device of the same officer opens its own shift and leaves the first intact.
 * endTime == null means the shift is still open.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "shifts", indexes = {
        @Index(name = "ix_shifts_user_device_open", columnList = "user_id, device_id, end_time")
})
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "username", nullable = false)
    private String username; // snapshot for reports

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "auto_closed", nullable = false)
    private boolean autoClosed = false; // true: kapanış logout ile değil, sistem tarafından yapıldı

    public Shift(User user, Zone zone, String deviceId, LocalDateTime startTime) {
        this.user = user;
        this.username = user.getUsername();
        this.zone = zone;
        this.deviceId = deviceId;
        this.startTime = startTime;
    }

    public boolean isOpen() {
        return endTime == null;
    }
}
