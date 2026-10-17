package io.mosip.liveness.eval;

import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.audit.MetricsSnapshot;
import io.mosip.liveness.core.PadAttackType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Working test to verify APCER calculation in MetricsCollector using only public API.
 */
class WorkingApcerTest {

    @Test
    void testPrintedPhotoApcer() {
        MetricsCollector collector = new MetricsCollector();

        // Test: 5 PRINTED_PHOTO attack presentations, 1 accepted as bona fide -> APCER = 0.2
        // Record 1 false acceptance (attack accepted as bona fide)
        collector.recordSessionEnd(true, PadAttackType.PRINTED_PHOTO, true);
        // Record 4 correct rejections (attack correctly rejected)
        for (int i = 0; i < 4; i++) {
            collector.recordSessionEnd(true, PadAttackType.PRINTED_PHOTO, false);
        }

        MetricsSnapshot snapshot = collector.snapshot();
        assertEquals(0.2, snapshot.printPhotoApcer(), 0.001);
    }

    @Test
    void testScreenReplayApcer() {
        MetricsCollector collector = new MetricsCollector();

        // Test: 4 SCREEN_REPLAY attack presentations, 0 accepted as bona fide -> APCER = 0.0
        // Record 4 correct rejections (all attacks correctly rejected)
        for (int i = 0; i < 4; i++) {
            collector.recordSessionEnd(true, PadAttackType.SCREEN_REPLAY, false);
        }

        MetricsSnapshot snapshot = collector.snapshot();
        assertEquals(0.0, snapshot.screenReplayApcer(), 0.001);
    }

    @Test
    void testVideoReplayApcer() {
        MetricsCollector collector = new MetricsCollector();

        // Test: 10 VIDEO_REPLAY attack presentations, 5 accepted as bona fide -> APCER = 0.5
        // Record 5 false acceptances (attacks accepted as bona fide)
        for (int i = 0; i < 5; i++) {
            collector.recordSessionEnd(true, PadAttackType.VIDEO_REPLAY, true);
        }
        // Record 5 correct rejections (attacks correctly rejected)
        for (int i = 0; i < 5; i++) {
            collector.recordSessionEnd(true, PadAttackType.VIDEO_REPLAY, false);
        }

        MetricsSnapshot snapshot = collector.snapshot();
        assertEquals(0.5, snapshot.videoReplayApcer(), 0.001);
    }

    @Test
    void testOtherApcerZeroAttempts() {
        MetricsCollector collector = new MetricsCollector();

        // Test: 0 OTHER attack presentations -> APCER = 0.0 (zero division case)
        MetricsSnapshot snapshot = collector.snapshot();
        assertEquals(0.0, snapshot.otherApcer(), 0.001);
    }

    @Test
    void testMixedAttacks() {
        MetricsCollector collector = new MetricsCollector();

        // PRINTED_PHOTO: 3 attempts, 1 accepted -> APCER = 0.333...
        collector.recordSessionEnd(true, PadAttackType.PRINTED_PHOTO, true);
        for (int i = 0; i < 2; i++) {
            collector.recordSessionEnd(true, PadAttackType.PRINTED_PHOTO, false);
        }

        // SCREEN_REPLAY: 0 attempts, 0 accepted -> APCER = 0.0
        // (no calls)

        // VIDEO_REPLAY: 4 attempts, 2 accepted -> APCER = 0.5
        for (int i = 0; i < 2; i++) {
            collector.recordSessionEnd(true, PadAttackType.VIDEO_REPLAY, true);
        }
        for (int i = 0; i < 2; i++) {
            collector.recordSessionEnd(true, PadAttackType.VIDEO_REPLAY, false);
        }

        // OTHER: 2 attempts, 0 accepted -> APCER = 0.0
        for (int i = 0; i < 2; i++) {
            collector.recordSessionEnd(true, PadAttackType.OTHER, false);
        }

        MetricsSnapshot snapshot = collector.snapshot();
        assertEquals(1.0/3.0, snapshot.printPhotoApcer(), 0.001);
        assertEquals(0.0, snapshot.screenReplayApcer(), 0.001);
        assertEquals(0.5, snapshot.videoReplayApcer(), 0.001);
        assertEquals(0.0, snapshot.otherApcer(), 0.001);
    }
}