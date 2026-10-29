package com.bilpark.backend.dto;

import lombok.Data;

import java.util.List;

/**
 * Admin tariff form. Values are validated in TariffService (400 INVALID_TARIFF).
 * vehicleTypes is the current form; the small/large fields are legacy (used only when vehicleTypes is absent).
 */
@Data
public class TariffRequest {
    private Double smallFirstHour;
    private Double smallExtraHour;
    private Double largeFirstHour;
    private Double largeExtraHour;
    private Integer freeMinutes;
    private String paidStart; // "HH:mm"
    private String paidEnd;   // "HH:mm", "00:00" = midnight
    private Double subscriptionSmallMonthly;
    private Double subscriptionLargeMonthly;
    private String note;
    private List<VehiclePriceRequest> vehicleTypes;
}
