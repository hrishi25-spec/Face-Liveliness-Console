package io.mosip.liveness.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arithmetic and recommendation rules of the threshold sweep. */
class ThresholdSweepTest {

    private static final int MIN_FRAMES = 5;
    private static final int WINDOW = 7;

    private static List<List<Double>> windows(int count, double score) {
        List<List<Double>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Double> w = new ArrayList<>();
            for (int f = 0; f < MIN_FRAMES; f++) w.add(score);
            out.add(w);
        }
        return out;
    }

    @Test
    void separatesCleanDistributionsAndPicksTheStrictestSafeThreshold() {
        ThresholdSweep.Result r = ThresholdSweep.run(
                windows(10, 0.90), windows(5, 0.30),
                MIN_FRAMES, WINDOW, 0.02, "test-scorer", false);

        assertEquals(19, r.rows().size(), "0.05 .. 0.95 in 0.05 steps");
        assertEquals("test-scorer", r.scorer());
        assertFalseFlagProxy(r);

        // Bona fide pass at every threshold <= 0.90; attacks fail at every
        // threshold > 0.30. The strictest threshold meeting both is 0.90.
        assertEquals(0.90, r.recommendedThreshold(), 1e-9);
        assertNotNull(r.recommendationBasis());

        ThresholdSweep.Row at030 = row(r, 0.30);
        assertEquals(1.0, at030.apcer(), 1e-9);   // attack median 0.30 >= 0.30 passes
        assertEquals(0.0, at030.bpcer(), 1e-9);

        ThresholdSweep.Row at090 = row(r, 0.90);
        assertEquals(0.0, at090.apcer(), 1e-9);
        assertEquals(0.0, at090.bpcer(), 1e-9);
        assertNotNull(at090.acer());

        ThresholdSweep.Row at095 = row(r, 0.95);
        assertEquals(1.0, at095.bpcer(), 1e-9, "0.90 medians fail a 0.95 threshold");
    }

    @Test
    void withoutAttackDataApcersAreNullAndTheNoteSaysSo() {
        ThresholdSweep.Result r = ThresholdSweep.run(
                windows(10, 0.90), List.of(),
                MIN_FRAMES, WINDOW, 0.02, "test-scorer", false);

        assertEquals(0.90, r.recommendedThreshold(), 1e-9,
                "BPCER-only recommendation must still be produced");
        assertTrue(r.note().contains("APCER unmeasured"), r.note());
        for (ThresholdSweep.Row row : r.rows()) {
            assertNull(row.apcer());
            assertNull(row.acer());
            assertNull(row.attackWindows());
        }
    }

    @Test
    void overlappingAttackDataFallsBackToMinimumAcer() {
        // Attacks score 0.99: they pass at every threshold, so no row has
        // APCER = 0. The fallback must be the minimum-ACER row.
        ThresholdSweep.Result r = ThresholdSweep.run(
                windows(10, 0.90), windows(5, 0.99),
                MIN_FRAMES, WINDOW, 0.02, "test-scorer", false);

        assertNotNull(r.recommendedThreshold());
        assertTrue(r.recommendationBasis().contains("Minimum ACER"), r.recommendationBasis());
        assertEquals(0.5, row(r, r.recommendedThreshold()).acer(), 1e-9,
                "APCER 1.0 + BPCER 0.0 => ACER 0.5");
    }

    @Test
    void shortWindowsAreExcludedAndCounted() {
        List<List<Double>> shortOnes = new ArrayList<>();
        shortOnes.add(List.of(0.9, 0.9));                  // 2 scores: never decidable
        List<List<Double>> bonaFide = windows(3, 0.90);
        bonaFide.addAll(shortOnes);

        ThresholdSweep.Result r = ThresholdSweep.run(
                bonaFide, windows(2, 0.30),
                MIN_FRAMES, WINDOW, 0.02, "test-scorer", false);

        assertEquals(1, r.excludedWindows(), "only the 2-score window is undecidable");
        assertEquals(3, r.rows().get(0).bonaFideWindows());
    }

    @Test
    void proxyFlagIsReportedToTheCaller() {
        ThresholdSweep.Result r = ThresholdSweep.run(
                windows(5, 0.90), windows(5, 0.30),
                MIN_FRAMES, WINDOW, 0.02, "test-scorer", true);
        assertTrue(r.attackDataIsProxy());
        assertTrue(r.note().contains("SIMULATED"), r.note());
    }

    @Test
    void noDataMeansNoRecommendation() {
        ThresholdSweep.Result r = ThresholdSweep.run(
                List.of(), List.of(),
                MIN_FRAMES, WINDOW, 0.02, "test-scorer", false);
        assertNull(r.recommendedThreshold());
        assertTrue(r.recommendationBasis().contains("No usable bona-fide"), r.recommendationBasis());
        for (ThresholdSweep.Row row : r.rows()) {
            assertEquals(0.0, row.bpcer(), 1e-9);   // vacuously nobody failed
        }
    }

    private static ThresholdSweep.Row row(ThresholdSweep.Result r, double threshold) {
        return r.rows().stream()
                .filter(row -> Math.abs(row.threshold() - threshold) < 1e-9)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for threshold " + threshold));
    }

    private static void assertFalseFlagProxy(ThresholdSweep.Result r) {
        assertTrue(!r.attackDataIsProxy());
    }
}
