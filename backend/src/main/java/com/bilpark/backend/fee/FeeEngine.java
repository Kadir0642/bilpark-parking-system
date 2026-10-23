package com.bilpark.backend.fee;

import com.bilpark.backend.model.VehicleType;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure fee calculation. Only minutes inside the daily paid window are billed; they are summed from
 * entry to exit and the tariff is applied once (no new "first hour" per day).
 */
public final class FeeEngine {

    private FeeEngine() {}

    public static FeeBreakdown calculate(TariffRules rules, VehicleType type, LocalDateTime entry, LocalDateTime exit) {
        if (entry == null || exit == null || !exit.isAfter(entry)) {
            return new FeeBreakdown(0, 0, 0, 0.0, List.of());
        }

        List<DaySegment> segments = new ArrayList<>();
        long paidSeconds = 0;
        for (LocalDate d = entry.toLocalDate(); !d.isAfter(exit.toLocalDate()); d = d.plusDays(1)) {
            LocalDateTime dayStart = d.atStartOfDay();
            LocalDateTime dayEnd = d.plusDays(1).atStartOfDay();
            LocalDateTime lo = max(entry, dayStart);
            LocalDateTime hi = min(exit, dayEnd);
            if (!lo.isBefore(hi)) continue;

            LocalDateTime wStart = d.atTime(rules.paidStart());
            LocalDateTime wEnd = LocalTime.MIDNIGHT.equals(rules.paidEnd()) ? dayEnd : d.atTime(rules.paidEnd());

            paidSeconds += add(segments, d, lo, min(hi, wStart), false);
            paidSeconds += add(segments, d, max(lo, wStart), min(hi, wEnd), true);
            add(segments, d, max(lo, wEnd), hi, false);
        }

        long totalMinutes = Duration.between(entry, exit).toMinutes();
        long paidMinutes = paidSeconds / 60;
        return new FeeBreakdown(totalMinutes, paidMinutes, totalMinutes - paidMinutes,
                priceFor(rules, type, paidMinutes), List.copyOf(segments));
    }

    /** Adds a non-empty segment. Returns the paid seconds of the added segment (0 if free/empty). */
    private static long add(List<DaySegment> out, LocalDate date, LocalDateTime from, LocalDateTime to, boolean paid) {
        if (!from.isBefore(to)) return 0;
        Duration dur = Duration.between(from, to);
        out.add(new DaySegment(date, from, to, paid, dur.toMinutes()));
        return paid ? dur.getSeconds() : 0;
    }

    /** Fee for already-computed paid minutes. */
    public static double priceFor(TariffRules rules, VehicleType type, long paidMinutes) {
        if (paidMinutes <= rules.freeMinutes()) return 0.0;
        boolean large = type == VehicleType.LARGE;
        double first = large ? rules.largeFirstHour() : rules.smallFirstHour();
        double extra = large ? rules.largeExtraHour() : rules.smallExtraHour();
        if (paidMinutes <= 60) return first;
        long extraHours = (paidMinutes - 60 + 59) / 60;
        return first + extraHours * extra;
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) { return a.isAfter(b) ? a : b; }
    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }
}
