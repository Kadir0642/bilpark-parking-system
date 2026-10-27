package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Client-generated check-in request id, used to make POST /check-in idempotent (offline queue replay). */
@Data
@NoArgsConstructor
@Entity
@Table(name = "check_in_requests")
public class CheckInRequest {

    @Id
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "plate", nullable = false, length = 20)
    private String plate;

    @Column(name = "park_spot_id")
    private Long parkSpotId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public CheckInRequest(String requestId, String plate, Long parkSpotId, LocalDateTime createdAt) {
        this.requestId = requestId;
        this.plate = plate;
        this.parkSpotId = parkSpotId;
        this.createdAt = createdAt;
    }
}
