package io.mosip.liveness.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the per-frame pipeline timers. The point of these is not that
 * timings are exact — it is that a phase which never ran is absent from the
 * payload, and that a throwing frame still stops its timer instead of leaking
 * it or silently swallowing the sample.
 */
class PipelineTimersTest {

    @BeforeEach
    void resetTimers() {
        PipelineTimers.reset();
    }

    @Test
    void snapshot_isEmptyBeforeAnythingRuns() {
        // Never-recorded phases are absent rather than zero: "the heuristic
        // fallback is in use" must be distinguishable from "never scored".
        assertTrue(PipelineTimers.snapshot().isEmpty());
    }

    @Test
    void timed_recordsCountAndDuration() throws InterruptedException {
        PipelineTimers.timed(PipelineTimers.DECODE, () -> {
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Map<String, PipelineTimers.PhaseTiming> snapshot = PipelineTimers.snapshot();
        assertEquals(1, snapshot.size());
        PipelineTimers.PhaseTiming decode = snapshot.get(PipelineTimers.DECODE);
        assertEquals(1, decode.count());
        assertTrue(decode.meanMs() >= 10.0,
                "a 15 ms sleep must not measure as " + decode.meanMs() + " ms");
        assertTrue(decode.maxMs() >= decode.meanMs());
    }

    @Test
    void sharesAreRelativeToTheMeasuredPhasesAndExcludeTotal() {
        PipelineTimers.timed(PipelineTimers.DECODE, () -> { });
        PipelineTimers.timed(PipelineTimers.FACE_DETECT, () -> { });
        PipelineTimers.timed(PipelineTimers.ONNX_SCORE, () -> { });
        PipelineTimers.timed(PipelineTimers.TOTAL, () -> { });

        Map<String, PipelineTimers.PhaseTiming> snapshot = PipelineTimers.snapshot();
        assertEquals(4, snapshot.size());

        double phaseShareSum = snapshot.entrySet().stream()
                .filter(e -> !PipelineTimers.TOTAL.equals(e.getKey()))
                .mapToDouble(e -> e.getValue().sharePct())
                .sum();
        assertTrue(Math.abs(phaseShareSum - 100.0) < 1.0,
                "phase shares must add up to ~100, got " + phaseShareSum);

        // TOTAL is wall clock for the whole request (JSON, JPA, audit), so a
        // share of the phase sum is meaningless and would exceed 100%.
        assertEquals(0.0, snapshot.get(PipelineTimers.TOTAL).sharePct());
        assertFalse(snapshot.containsKey(PipelineTimers.HEURISTIC_SCORE),
                "a phase that never ran must not appear");
    }

    @Test
    void timerStillStopsWhenTheBodyThrows() {
        assertThrows(IllegalStateException.class, () ->
                PipelineTimers.timed(PipelineTimers.ONNX_SCORE, () -> {
                    throw new IllegalStateException("boom");
                }));

        // A frame that fails mid-pipeline still has to be counted, otherwise the
        // timings describe only the frames that succeeded.
        assertEquals(1, PipelineTimers.snapshot().get(PipelineTimers.ONNX_SCORE).count());
    }

    @Test
    void supplierOverloadReturnsTheBodyValue() {
        assertEquals("result", PipelineTimers.timed(PipelineTimers.DECODE, () -> "result"));
        assertEquals(1, PipelineTimers.snapshot().get(PipelineTimers.DECODE).count());
    }

    @Test
    void resetClearsRecordedSamples() {
        PipelineTimers.timed(PipelineTimers.DECODE, () -> { });
        assertFalse(PipelineTimers.snapshot().isEmpty());

        PipelineTimers.reset();
        assertTrue(PipelineTimers.snapshot().isEmpty());
    }
}