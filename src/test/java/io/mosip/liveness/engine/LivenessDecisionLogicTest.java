package io.mosip.liveness.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessDecisionLogicTest {

    @Test
    void medianOddCountReturnsMiddleValue() {
        assertEquals(0.5, LivenessDecisionLogic.median(List.of(0.1, 0.5, 0.9)), 1e-9);
    }

    @Test
    void medianEvenCountAveragesMiddleTwo() {
        // sorted: [0.1, 0.2, 0.4, 0.5] -> median = (0.2 + 0.4) / 2 = 0.3
        assertEquals(0.3, LivenessDecisionLogic.median(List.of(0.4, 0.5, 0.1, 0.2)), 1e-9);
    }

    @Test
    void medianIsOrderIndependent() {
        double a = LivenessDecisionLogic.median(List.of(0.9, 0.2, 0.6));
        double b = LivenessDecisionLogic.median(List.of(0.2, 0.6, 0.9));
        assertEquals(a, b, 1e-12);
    }

    @Test
    void medianRejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> LivenessDecisionLogic.median(List.of()));
    }

    @ParameterizedTest
    @CsvSource({
            "0.95, 0.80, PROCEED_PASSIVE",
            "0.80, 0.80, PROCEED_PASSIVE",   // >= threshold passes
            "0.79, 0.80, ESCALATE_ACTIVE"
    })
    void decidePassiveRespectsThreshold(double median, double threshold, String expected) {
        var outcome = LivenessDecisionLogic.decidePassive(median, threshold, false);
        assertEquals(expected, outcome.name());
    }

    @Test
    void padFlagAlwaysBlocksRegardlessOfScore() {
        assertEquals(LivenessDecisionLogic.PassiveOutcome.PAD_BLOCK,
                LivenessDecisionLogic.decidePassive(0.99, 0.5, true));
        assertTrue(LivenessDecisionLogic.decidePassive(0.99, 0.5, true)
                == LivenessDecisionLogic.PassiveOutcome.PAD_BLOCK);
    }

    // ---- rolling-window decision (cold start + median) ----

    @Test
    void windowStaysUndecidableUntilMinFramesAccumulate() {
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            scores.add(0.95);                       // frames 1..4
            assertTrue(LivenessDecisionLogic.decidePassiveWindow(scores, 5, 7, 0.80).isEmpty(),
                    "cold start: frame " + i + " must not decide yet");
        }
        scores.add(0.95);                           // frame 5 = minFrames
        Optional<LivenessDecisionLogic.PassiveOutcome> decision =
                LivenessDecisionLogic.decidePassiveWindow(scores, 5, 7, 0.80);
        assertEquals(LivenessDecisionLogic.PassiveOutcome.PROCEED_PASSIVE, decision.orElse(null));
    }

    @Test
    void windowWarmButBelowThresholdEscalates() {
        List<Double> scores = new ArrayList<>(List.of(0.30, 0.35, 0.40, 0.30, 0.25));
        assertEquals(LivenessDecisionLogic.PassiveOutcome.ESCALATE_ACTIVE,
                LivenessDecisionLogic.decidePassiveWindow(scores, 5, 7, 0.80).orElseThrow());
    }

    @Test
    void medianOfTheWindowRejectsASingleBlurryFrame() {
        // One dim/blurry frame among five good ones. The mean (0.76) would fall
        // below the threshold and escalate; the median (0.90) does not.
        List<Double> scores = new ArrayList<>(List.of(0.90, 0.90, 0.20, 0.90, 0.90));
        double mean = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        assertTrue(mean < 0.80, "precondition: the mean would escalate");
        assertEquals(LivenessDecisionLogic.PassiveOutcome.PROCEED_PASSIVE,
                LivenessDecisionLogic.decidePassiveWindow(scores, 5, 7, 0.80).orElseThrow());
    }

    @Test
    void onlyTheMostRecentWindowFramesCount() {
        // 12 scores: the first seven are junk (old frames scrolled out), the last
        // five are good. The window must be decided on the recent frames.
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < 7; i++) scores.add(0.10);
        for (int i = 0; i < 5; i++) scores.add(0.95);

        // median of the LAST 7 scores: [0.10, 0.95, 0.95, 0.95, 0.95, 0.95, 0.95]
        assertEquals(LivenessDecisionLogic.PassiveOutcome.PROCEED_PASSIVE,
                LivenessDecisionLogic.decidePassiveWindow(scores, 5, 7, 0.80).orElseThrow());

        // ...while the full history's mean would be far below the threshold.
        double mean = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        assertTrue(mean < 0.80);
    }

    @Test
    void windowToleranceForDegenerateSizes() {
        // minFrames <= 0 is treated as 1 (never decide on zero frames),
        // windowFrames < minFrames still decides once warm.
        assertTrue(LivenessDecisionLogic.decidePassiveWindow(List.of(), 0, 0, 0.80).isEmpty());
        assertEquals(LivenessDecisionLogic.PassiveOutcome.PROCEED_PASSIVE,
                LivenessDecisionLogic.decidePassiveWindow(List.of(0.9), 1, 1, 0.80).orElseThrow());
        assertFalse(LivenessDecisionLogic.decidePassiveWindow(List.of(0.1), 1, 1, 0.80).isEmpty());
    }
}
