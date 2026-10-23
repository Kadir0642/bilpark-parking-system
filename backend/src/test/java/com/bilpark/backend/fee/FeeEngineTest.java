package com.bilpark.backend.fee;

import com.bilpark.backend.model.VehicleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeeEngineTest {

    private static final TariffRules DEFAULT =
            new TariffRules(25, 15, 50, 30, 5, LocalTime.of(8, 0), LocalTime.of(22, 0));

    private static LocalDateTime t(String s) { return LocalDateTime.parse(s); }

    // type, entry, exit, expected paid minutes, expected fee
    @ParameterizedTest
    @CsvSource({
            // exit == entry
            "SMALL, 2026-10-21T10:00, 2026-10-21T10:00, 0, 0",
            // daytime stays and the free-minute limit
            "SMALL, 2026-10-21T10:00, 2026-10-21T10:05, 5, 0",
            "SMALL, 2026-10-21T10:00, 2026-10-21T10:06, 6, 25",
            "SMALL, 2026-10-21T10:00, 2026-10-21T10:30, 30, 25",
            "SMALL, 2026-10-21T10:00, 2026-10-21T11:00, 60, 25",
            "SMALL, 2026-10-21T10:00, 2026-10-21T11:01, 61, 40",
            "SMALL, 2026-10-21T10:00, 2026-10-21T12:00, 120, 40",
            "SMALL, 2026-10-21T10:00, 2026-10-21T12:01, 121, 55",
            // overnight carry-over
            "SMALL, 2026-10-21T21:30, 2026-10-22T08:45, 75, 40",
            "SMALL, 2026-10-21T21:00, 2026-10-22T09:00, 120, 40",
            // window boundaries
            "SMALL, 2026-10-21T22:00, 2026-10-22T08:00, 0, 0",
            "SMALL, 2026-10-21T07:59, 2026-10-21T08:01, 1, 0",
            "SMALL, 2026-10-21T07:50, 2026-10-21T08:30, 30, 25",
            "SMALL, 2026-10-21T21:59, 2026-10-21T22:01, 1, 0",
            // entirely at night
            "SMALL, 2026-10-21T23:00, 2026-10-22T06:00, 0, 0",
            "SMALL, 2026-10-21T02:00, 2026-10-21T05:00, 0, 0",
            // multi-day: tariff applied once over the summed paid minutes
            "SMALL, 2026-10-21T00:00, 2026-10-22T00:00, 840, 220",
            "SMALL, 2026-10-21T10:00, 2026-10-23T10:00, 1680, 430",
            "SMALL, 2026-10-21T00:00, 2026-10-24T00:00, 2520, 640",
            // vehicle types
            "SMALL, 2026-10-21T10:00, 2026-10-21T11:30, 90, 40",
            "LARGE, 2026-10-21T10:00, 2026-10-21T11:30, 90, 80",
            "LARGE, 2026-10-21T10:00, 2026-10-21T10:30, 30, 50",
            "LARGE, 2026-10-21T10:00, 2026-10-21T10:05, 5, 0",
            "LARGE, 2026-10-21T10:00, 2026-10-23T10:00, 1680, 860",
    })
    void defaultTariff(VehicleType type, String entry, String exit, long paid, double fee) {
        FeeBreakdown b = FeeEngine.calculate(DEFAULT, type, t(entry), t(exit));
        assertEquals(paid, b.paidMinutes());
        assertEquals(fee, b.fee());
        assertEquals(b.totalMinutes() - b.paidMinutes(), b.freeMinutes());
        assertEquals(paid, b.segments().stream().filter(DaySegment::paid).mapToLong(DaySegment::minutes).sum());
        assertContiguous(b, t(entry), t(exit));
    }

    @Test
    void exitBeforeEntryIsZero() {
        FeeBreakdown b = FeeEngine.calculate(DEFAULT, VehicleType.SMALL, t("2026-10-21T12:00"), t("2026-10-21T10:00"));
        assertEquals(0, b.paidMinutes());
        assertEquals(0, b.totalMinutes());
        assertEquals(0.0, b.fee());
        assertTrue(b.segments().isEmpty());
    }

    @Test
    void overnightSegmentsSplitAtMidnightAndWindowEdges() {
        FeeBreakdown b = FeeEngine.calculate(DEFAULT, VehicleType.SMALL, t("2026-10-21T20:00"), t("2026-10-22T10:00"));
        List<DaySegment> s = b.segments();
        assertEquals(4, s.size());
        assertSegment(s.get(0), "2026-10-21T20:00", "2026-10-21T22:00", true, 120);
        assertSegment(s.get(1), "2026-10-21T22:00", "2026-10-22T00:00", false, 120);
        assertSegment(s.get(2), "2026-10-22T00:00", "2026-10-22T08:00", false, 480);
        assertSegment(s.get(3), "2026-10-22T08:00", "2026-10-22T10:00", true, 120);
        assertEquals(LocalDate.of(2026, 10, 21), s.get(0).date());
        assertEquals(LocalDate.of(2026, 10, 22), s.get(3).date());
        assertEquals(240, b.paidMinutes());
        assertEquals(25 + 3 * 15, b.fee()); // 25 + ceil(180/60) * 15
        assertEquals(840, b.totalMinutes());
        assertEquals(600, b.freeMinutes());
    }

    @Test
    void customWindowNineToEighteen() {
        TariffRules r = new TariffRules(25, 15, 50, 30, 5, LocalTime.of(9, 0), LocalTime.of(18, 0));
        FeeBreakdown b = FeeEngine.calculate(r, VehicleType.SMALL, t("2026-10-21T08:00"), t("2026-10-21T19:00"));
        assertEquals(540, b.paidMinutes());
        assertEquals(25 + 8 * 15, b.fee());
        assertEquals(3, b.segments().size());
        assertFalse(b.segments().get(0).paid());
        assertTrue(b.segments().get(1).paid());
        assertFalse(b.segments().get(2).paid());
        assertContiguous(b, t("2026-10-21T08:00"), t("2026-10-21T19:00"));
    }

    @Test
    void paidEndMidnightMeansEndOfDay() {
        TariffRules r = new TariffRules(25, 15, 50, 30, 5, LocalTime.of(8, 0), LocalTime.MIDNIGHT);
        FeeBreakdown b = FeeEngine.calculate(r, VehicleType.SMALL, t("2026-10-21T21:00"), t("2026-10-22T01:00"));
        assertEquals(180, b.paidMinutes()); // 21:00-24:00 paid, 00:00-01:00 free
        assertEquals(25 + 2 * 15, b.fee());
        assertEquals(2, b.segments().size());
        assertSegment(b.segments().get(0), "2026-10-21T21:00", "2026-10-22T00:00", true, 180);
        assertSegment(b.segments().get(1), "2026-10-22T00:00", "2026-10-22T01:00", false, 60);
    }

    @Test
    void midnightToMidnightIsWholeDayPaid() {
        TariffRules r = new TariffRules(25, 15, 50, 30, 5, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);
        FeeBreakdown b = FeeEngine.calculate(r, VehicleType.SMALL, t("2026-10-21T23:00"), t("2026-10-22T01:00"));
        assertEquals(120, b.paidMinutes());
        assertEquals(40.0, b.fee());
        assertEquals(2, b.segments().size());
        assertTrue(b.segments().stream().allMatch(DaySegment::paid));
    }

    @Test
    void priceForUsesPaidMinutesOnly() {
        assertEquals(0.0, FeeEngine.priceFor(DEFAULT, VehicleType.SMALL, 5));
        assertEquals(25.0, FeeEngine.priceFor(DEFAULT, VehicleType.SMALL, 6));
        assertEquals(50.0, FeeEngine.priceFor(DEFAULT, VehicleType.LARGE, 60));
        assertEquals(80.0, FeeEngine.priceFor(DEFAULT, VehicleType.LARGE, 61));
    }

    private static void assertSegment(DaySegment s, String start, String end, boolean paid, long minutes) {
        assertEquals(t(start), s.start());
        assertEquals(t(end), s.end());
        assertEquals(paid, s.paid());
        assertEquals(minutes, s.minutes());
    }

    private static void assertContiguous(FeeBreakdown b, LocalDateTime entry, LocalDateTime exit) {
        List<DaySegment> s = b.segments();
        if (!exit.isAfter(entry)) {
            assertTrue(s.isEmpty());
            return;
        }
        assertEquals(entry, s.get(0).start());
        assertEquals(exit, s.get(s.size() - 1).end());
        for (int i = 1; i < s.size(); i++) {
            assertEquals(s.get(i - 1).end(), s.get(i).start());
        }
    }
}
