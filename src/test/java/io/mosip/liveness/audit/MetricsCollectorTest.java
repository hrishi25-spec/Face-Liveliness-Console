package io.mosip.liveness.audit;

import io.mosip.liveness.core.PadAttackType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsCollectorTest {

    @Test
    void unlabeledFailuresAndPadBlockTallyDoNotChangeApcerSamples() {
        MetricsCollector collector = new MetricsCollector();
        collector.recordSessionEnd(true, PadAttackType.PRINTED_PHOTO, true);

        collector.recordPadBlock();
        collector.recordSessionEnd(true, null, false);

        MetricsSnapshot snapshot = collector.snapshot();
        assertEquals(1.0, snapshot.printPhotoApcer(), 1e-9);
        assertEquals(0.0, snapshot.screenReplayApcer(), 1e-9);
        assertEquals(1L, snapshot.padBlocks());
    }
}
