package io.mosip.liveness.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of {@link ProbabilityCalibration}: it must spread an over-confident
 * genuine tail while leaving the decision operating point — and everything
 * below it — exactly where it was.
 */
class ProbabilityCalibrationTest {

    private static final double PIVOT = ProbabilityCalibration.DEFAULT_PIVOT;

    @Test
    void temperatureOneIsTheIdentity() {
        ProbabilityCalibration c = ProbabilityCalibration.identity();
        assertFalse(c.isActive());
        for (double p : new double[]{0.0, 0.0005, 0.5, 0.80, 0.995, 0.99999, 1.0}) {
            assertEquals(p, c.apply(p), 1e-12, "identity must not move " + p);
        }
    }

    @Test
    void scoresAtOrBelowThePivotAreUntouched() {
        ProbabilityCalibration c = ProbabilityCalibration.ofTemperature(4.0);
        for (double p : new double[]{0.0, 0.00055, 0.25, 0.5, 0.79, 0.80}) {
            assertEquals(p, c.apply(p), 1e-12, "at or below the pivot the score must be unchanged: " + p);
        }
    }

    @Test
    void pivotIsAFixedPointSoTheThresholdDoesNotMove() {
        ProbabilityCalibration c = ProbabilityCalibration.ofTemperature(4.0);
        assertEquals(PIVOT, c.apply(PIVOT), 1e-12);
        // Continuity from just above the pivot.
        assertEquals(PIVOT, c.apply(PIVOT + 1e-9), 1e-6);
    }

    @Test
    void thePassFailVerdictAtTheDefaultThresholdIsPreserved() {
        ProbabilityCalibration c = ProbabilityCalibration.ofTemperature(4.0);
        for (double p : new double[]{0.10, 0.50, 0.795, 0.80, 0.801, 0.85, 0.95, 0.999, 1.0}) {
            boolean rawPass = p >= PIVOT;
            boolean calibratedPass = c.apply(p) >= PIVOT;
            assertEquals(rawPass, calibratedPass,
                    "threshold decision must be unchanged for " + p);
        }
    }

    @Test
    void theMapIsMonotonic() {
        ProbabilityCalibration c = ProbabilityCalibration.ofTemperature(4.0);
        double previous = -1.0;
        for (int i = 0; i <= 1000; i++) {
            double p = i / 1000.0;
            double calibrated = c.apply(p);
            assertTrue(calibrated >= previous, "must be non-decreasing at " + p);
            previous = calibrated;
        }
    }

    @Test
    void aSaturatedGenuineScoreIsSpreadIntoAReadableBand() {
        ProbabilityCalibration c = ProbabilityCalibration.ofTemperature(4.0);
        // The raw MiniFASNet probability for the committed genuine fixture.
        double calibrated = c.apply(0.99499);
        assertTrue(calibrated > 0.85 && calibrated < 0.95,
                "0.99499 should land in a readable band, was " + calibrated);

        // Even a near-certain frame must not read as exactly 1.
        assertTrue(c.apply(0.9999) < 1.0, "a finite input must never calibrate to exactly 1.0");
    }

    @Test
    void attackScoresStayLow() {
        ProbabilityCalibration c = ProbabilityCalibration.ofTemperature(4.0);
        // The print-attack fixture's live-class probability.
        assertEquals(0.0005488, c.apply(0.0005488), 1e-12);
    }

    @Test
    void nanPassesThroughAsTheNoScoreSentinel() {
        assertTrue(Double.isNaN(ProbabilityCalibration.ofTemperature(4.0).apply(Double.NaN)));
    }

    @Test
    void rejectsAPivotOutsideTheOpenUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new ProbabilityCalibration(0.0, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new ProbabilityCalibration(1.0, 2.0));
    }

    @Test
    void rejectsATemperatureBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> ProbabilityCalibration.ofTemperature(0.99));
        assertThrows(IllegalArgumentException.class, () -> ProbabilityCalibration.ofTemperature(Double.NaN));
    }
}
