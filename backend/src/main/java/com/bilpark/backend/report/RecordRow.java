package com.bilpark.backend.report;

import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.PaymentMethod;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.VehicleType;

import java.time.LocalDateTime;

/** Flat projection of a parking record for reports (no entity graph is loaded). */
public record RecordRow(Long id, String plate, StreetLocation street, Long zoneId, String zoneName,
                        LocalDateTime entryTime, LocalDateTime exitTime, VehicleType vehicleType, Double fee,
                        ParkingStatus status, boolean debtPaid, PaymentMethod paymentMethod,
                        String collectedBy, String deviceId, Long tariffId, Integer paidMinutes,
                        ParkingStatus cancelledFrom, LocalDateTime cancelledAt, String cancelReason) {

    public double feeOrZero() {
        return fee == null ? 0.0 : fee;
    }
}
