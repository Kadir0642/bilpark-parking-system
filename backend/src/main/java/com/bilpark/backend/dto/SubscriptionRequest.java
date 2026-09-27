package com.bilpark.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SubscriptionRequest {

    @NotBlank(message = "License plate cannot be blank")
    @Pattern(regexp = "^[A-Za-z0-9 ]{5,11}$", message = "Invalid plate format")
    private String licensePlate;

    @NotBlank(message = "Vehicle type cannot be blank")
    private String vehicleType; // "SMALL" or "LARGE"

    private String ownerName;
    private String ownerPhone;
}
