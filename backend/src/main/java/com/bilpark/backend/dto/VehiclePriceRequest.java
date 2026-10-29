package com.bilpark.backend.dto;

import lombok.Data;

/** One vehicle type row of the admin tariff form. */
@Data
public class VehiclePriceRequest {
    private String code;
    private Boolean enabled; // null = true
    private Double firstHour;
    private Double extraHour;
    private Double subscriptionMonthly;
}
