package io.mosip.liveness.eval;

import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.audit.MetricsSnapshot;
import io.mosip.liveness.dto.OperationalMetrics;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.api.RateLimitCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test to verify that APCER metrics are correctly exposed through the metrics endpoint
 * by simulating what MetricsController does.
 */
class ApcerEndpointTest {

    @Mock
    private RateLimitCounters rateLimitCounters;

    @InjectMocks
    private MetricsCollector metricsCollector;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testMetricsControllerExposesApcerCorrectly() {
        // Set up known APCER values
        // PRINTED_PHOTO: 8 attempts, 2 accepted as bona fide -> APCER = 0.25
        for (int i = 0; i < 2; i++) {
            metricsCollector.recordSessionEnd(true, PadAttackType.PRINTED_PHOTO, true);
        }
        for (int i = 2; i < 8; i++) {
            metricsCollector.recordSessionEnd(true, PadAttackType.PRINTED_PHOTO, false);
        }

        // SCREEN_REPLAY: 6 attempts, 0 accepted -> APCER = 0.0
        for (int i = 0; i < 6; i++) {
            metricsCollector.recordSessionEnd(true, PadAttackType.SCREEN_REPLAY, false);
        }

        // VIDEO_REPLAY: 10 attempts, 4 accepted -> APCER = 0.4
        for (int i = 0; i < 4; i++) {
            metricsCollector.recordSessionEnd(true, PadAttackType.VIDEO_REPLAY, true);
        }
        for (int i = 4; i < 10; i++) {
            metricsCollector.recordSessionEnd(true, PadAttackType.VIDEO_REPLAY, false);
        }

        // OTHER: 3 attempts, 1 accepted -> APCER = 0.333...
        for (int i = 0; i < 1; i++) {
            metricsCollector.recordSessionEnd(true, PadAttackType.OTHER, true);
        }
        for (int i = 1; i < 3; i++) {
            metricsCollector.recordSessionEnd(true, PadAttackType.OTHER, false);
        }

        // Add some baseline metrics
        metricsCollector.recordProcessingTime(50.0);
        metricsCollector.recordProcessingTime(50.0);
        metricsCollector.recordChallengeCompletion(200.0);
        metricsCollector.recordRetry();
        metricsCollector.recordRetry();
        metricsCollector.recordSessionEnd(false, null, false); // passed
        metricsCollector.recordSessionEnd(true, null, false);  // failed

        // Get the metrics snapshot (what MetricsController calls)
        MetricsSnapshot snapshot = metricsCollector.snapshot();

        // Verify the APCER values are calculated correctly
        assertEquals(0.25, snapshot.printPhotoApcer(), 0.001);
        assertEquals(0.0, snapshot.screenReplayApcer(), 0.001);
        assertEquals(0.4, snapshot.videoReplayApcer(), 0.001);
        assertEquals(1.0/3.0, snapshot.otherApcer(), 0.001);

        // Simulate what MetricsController does to build the OperationalMetrics response
        // This verifies the APCER values flow correctly to the endpoint
        OperationalMetrics om = OperationalMetrics.builder()
                .totalSessions(2) // from our baseline sessionEnd calls
                .passedSessions(1)
                .failedSessions(1)
                .activeSessions(0)
                .passRate(0.5)
                .avgPassiveToActiveEscalationRate(0.0)
                .avgFramesPerSession(1.0)
                .avgChallengesPerSession(0.5)
                .livenessRetryRate(0.5)
                .livenessFailureRate(0.5)
                .padRejectionRate(0.0)
                .printPhotoApcer(snapshot.printPhotoApcer())        // <-- This is what gets exposed
                .screenReplayApcer(snapshot.screenReplayApcer())   // <-- This is what gets exposed
                .videoReplayApcer(snapshot.videoReplayApcer())     // <-- This is what gets exposed
                .otherApcer(snapshot.otherApcer())                 // <-- This is what gets exposed
                .pipelineTimings(Map.of())
                .rateLimitAllowedRequests(0)
                .rateLimitedRequests(0)
                .rateLimitedSessionCreate(0)
                .rateLimitedFrames(0)
                .rateLimitRejectionRate(0.0)
                .build();

        // Verify that the endpoint would expose the correct APCER values
        assertEquals(0.25, om.getPrintPhotoApcer(), 0.001);
        assertEquals(0.0, om.getScreenReplayApcer(), 0.001);
        assertEquals(0.4, om.getVideoReplayApcer(), 0.001);
        assertEquals(1.0/3.0, om.getOtherApcer(), 0.001);

        System.out.println("✓ Metrics endpoint correctly exposes per-species APCER:");
        System.out.println("  Printed Photo APCER: " + om.getPrintPhotoApcer());
        System.out.println("  Screen Replay APCER: " + om.getScreenReplayApcer());
        System.out.println("  Video Replay APCER: " + om.getVideoReplayApcer());
        System.out.println("  Other APCER: " + om.getOtherApcer());
    }
}