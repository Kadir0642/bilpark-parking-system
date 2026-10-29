package com.bilpark.backend.report;

import com.bilpark.backend.settings.SettingsService.PerformanceWeights;

/**
 * Personnel score 0-100 relative to the zone average (3-month zone reference).
 * Pure functions, no I/O.
 *   higher-is-better: at the zone average = 75, 1.33x average or more = 100
 *   lower-is-better:  at the zone average = 75, 0 = 100, 2x average or more = 0
 *   punctuality:      absolute (share of on-time work days)
 *   no reference (zero): neutral 75 (100 for lower-is-better)
 */
public final class PerformanceScorer {

    public static final double NEUTRAL = 75.0;

    private PerformanceScorer() {}

    /** Officer values of the month. Rates are fractions 0..1. */
    public record Inputs(double hours, double throughput, double collectionPerHour, double onTimeRate,
                         double runawayRate, double cancelRate) {}

    /** Zone reference values. Rates are fractions 0..1. */
    public record Reference(double hours, double throughput, double collectionPerHour,
                            double runawayRate, double cancelRate) {
        public static final Reference NONE = new Reference(0, 0, 0, 0, 0);
    }

    public static double higherIsBetter(double value, double ref) {
        return ref > 0 ? clamp(NEUTRAL * value / ref) : NEUTRAL;
    }

    public static double lowerIsBetter(double value, double ref) {
        return ref > 0 ? clamp(NEUTRAL * (2 - value / ref)) : 100.0;
    }

    public static double punctuality(double onTimeRate) {
        return clamp(onTimeRate * 100.0);
    }

    public static double score(Inputs in, Reference ref, PerformanceWeights w) {
        double total = w.hours() * higherIsBetter(in.hours(), ref.hours())
                + w.punctuality() * punctuality(in.onTimeRate())
                + w.throughput() * higherIsBetter(in.throughput(), ref.throughput())
                + w.collection() * higherIsBetter(in.collectionPerHour(), ref.collectionPerHour())
                + w.runaway() * lowerIsBetter(in.runawayRate(), ref.runawayRate())
                + w.cancellation() * lowerIsBetter(in.cancelRate(), ref.cancelRate());
        return Math.round(total / 100.0 * 10.0) / 10.0;
    }

    public static String label(double score) {
        if (score >= 85) return "Çok iyi";
        if (score >= 65) return "İyi";
        return "Geliştirilmeli";
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }
}
