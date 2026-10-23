package com.bilpark.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubscriptionRequest {

    @NotBlank(message = "License plate cannot be blank")
    private String licensePlate;

    @NotBlank(message = "Vehicle type cannot be blank")
    private String vehicleType; // "SMALL" or "LARGE"

    private String ownerName;
    private String ownerPhone;
}
