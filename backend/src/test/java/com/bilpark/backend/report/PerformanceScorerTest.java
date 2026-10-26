package com.bilpark.backend.report;

import com.bilpark.backend.report.PerformanceScorer.Inputs;
import com.bilpark.backend.report.PerformanceScorer.Reference;
import com.bilpark.backend.settings.SettingsService.PerformanceWeights;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerformanceScorerTest {

    private static final Reference REF = new Reference(100, 5, 200, 0.1, 0.05);

    @Test
    void averageOfficerGets75OnRelativeMetrics() {
        assertEquals(75.0, PerformanceScorer.higherIsBetter(100, 100), 1e-9);
        assertEquals(75.0, PerformanceScorer.lowerIsBetter(0.1, 0.1), 1e-9);
        // 20*75 + 15*100 (always on time) + 20*75 + 20*75 + 15*75 + 10*75 = 7875 -> 78.75
        double s = PerformanceScorer.score(new Inputs(100, 5, 200, 1.0, 0.1, 0.05), REF, PerformanceWeights.DEFAULT);
        assertEquals(78.8, s, 1e-9);
    }

    @Test
    void doubleTheAverageIsCappedAt100() {
        assertEquals(100.0, PerformanceScorer.higherIsBetter(200, 100), 1e-9);
        assertEquals(100.0, PerformanceScorer.higherIsBetter(133.4, 100), 1e-9);
        assertEquals(0.0, PerformanceScorer.higherIsBetter(0, 100), 1e-9);
        PerformanceWeights onlyThroughput = new PerformanceWeights(0, 0, 100, 0, 0, 0, "08:00", 5);
        assertEquals(100.0, PerformanceScorer.score(new Inputs(0, 10, 0, 0, 0, 0), REF, onlyThroughput), 1e-9);
    }

    @Test
    void lowerIsBetterMetrics() {
        assertEquals(100.0, PerformanceScorer.lowerIsBetter(0, 0.1), 1e-9);
        assertEquals(0.0, PerformanceScorer.lowerIsBetter(0.2, 0.1), 1e-9);
        assertEquals(0.0, PerformanceScorer.lowerIsBetter(0.5, 0.1), 1e-9);
        assertEquals(37.5, PerformanceScorer.lowerIsBetter(0.15, 0.1), 1e-9);
    }

    @Test
    void zeroReferenceIsNeutral() {
        assertEquals(75.0, PerformanceScorer.higherIsBetter(50, 0), 1e-9);
        assertEquals(100.0, PerformanceScorer.lowerIsBetter(0.5, 0), 1e-9);
        PerformanceWeights hoursOnly = new PerformanceWeights(100, 0, 0, 0, 0, 0, "08:00", 5);
        assertEquals(75.0, PerformanceScorer.score(new Inputs(10, 1, 1, 0, 0, 0), Reference.NONE, hoursOnly), 1e-9);
    }

    @Test
    void punctualityIsAbsolute() {
        PerformanceWeights only = new PerformanceWeights(0, 100, 0, 0, 0, 0, "08:00", 5);
        assertEquals(50.0, PerformanceScorer.score(new Inputs(0, 0, 0, 0.5, 0, 0), REF, only), 1e-9);
        assertEquals(0.0, PerformanceScorer.score(new Inputs(0, 0, 0, 0, 0, 0), REF, only), 1e-9);
    }

    @Test
    void weightsChangeTheTotal() {
        Inputs in = new Inputs(100, 5, 200, 0.0, 0.1, 0.05); // average everywhere, never on time
        double withDefault = PerformanceScorer.score(in, REF, PerformanceWeights.DEFAULT);
        PerformanceWeights heavyPunctuality = new PerformanceWeights(10, 40, 10, 10, 15, 15, "08:00", 5);
        double withOther = PerformanceScorer.score(in, REF, heavyPunctuality);
        assertEquals(63.8, withDefault, 1e-9);  // (85*75)/100 = 63.75 -> 63.8
        assertEquals(45.0, withOther, 1e-9);    // 60*75/100
    }

    @Test
    void labelThresholds() {
        assertEquals("Çok iyi", PerformanceScorer.label(85));
        assertEquals("İyi", PerformanceScorer.label(84.9));
        assertEquals("İyi", PerformanceScorer.label(65));
        assertEquals("Geliştirilmeli", PerformanceScorer.label(64.9));
        assertEquals("Çok iyi", PerformanceScorer.label(100));
    }
}
