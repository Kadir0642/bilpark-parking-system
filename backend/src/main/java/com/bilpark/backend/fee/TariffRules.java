package com.bilpark.backend.fee;

import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.TariffVehiclePrice;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable price and paid-window rules used by the fee engine. paidEnd 00:00 means midnight. */
public record TariffRules(Map<String, Price> prices, int freeMinutes, LocalTime paidStart, LocalTime paidEnd) {

    /** First-hour and extra-hour price of one vehicle type. */
    public record Price(double firstHour, double extraHour) {}

    public TariffRules {
        prices = Map.copyOf(prices);
    }

    /** Uses every row that has prices (enabled or not), so a parked vehicle of a since-disabled type still gets its price. */
    public static TariffRules from(Tariff t) {
        Map<String, Price> m = new LinkedHashMap<>();
        for (TariffVehiclePrice p : t.getPrices()) {
            if (p.getFirstHour() != null && p.getExtraHour() != null) {
                m.put(p.getVehicleType(), new Price(p.getFirstHour(), p.getExtraHour()));
            }
        }
        return new TariffRules(m, t.getFreeMinutes(), t.getPaidStart(), t.getPaidEnd());
    }

    /** Price of a type; null code = SMALL; missing row falls back to SMALL, then to any price. Null if none at all. */
    public Price priceOf(String typeCode) {
        Price p = prices.get(typeCode == null ? "SMALL" : typeCode);
        if (p == null) p = prices.get("SMALL");
        if (p == null && !prices.isEmpty()) p = prices.values().iterator().next();
        return p;
    }
}
