package com.bilpark.backend.fee;

import com.bilpark.backend.model.Tariff;

import java.time.LocalTime;

/** Immutable price and paid-window rules used by the fee engine. paidEnd 00:00 means midnight. */
public record TariffRules(double smallFirstHour, double smallExtraHour,
                          double largeFirstHour, double largeExtraHour,
                          int freeMinutes, LocalTime paidStart, LocalTime paidEnd) {

    public static TariffRules from(Tariff t) {
        return new TariffRules(t.getSmallFirstHour(), t.getSmallExtraHour(),
                t.getLargeFirstHour(), t.getLargeExtraHour(),
                t.getFreeMinutes(), t.getPaidStart(), t.getPaidEnd());
    }
}
