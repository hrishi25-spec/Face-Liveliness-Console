package io.mosip.liveness.services;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.eval.ThresholdSweep;
import io.mosip.liveness.models.entity.FrameEvent;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.LivenessStage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Calibration wiring: how frame_events become decision windows and how the
 * sweep result is assembled (recorded samples vs. labelled proxy corpus).
 */
class ThresholdCalibrationServiceTest {

    private FrameEventRepository frameEventRepo;
    private ConfigService configService;
    private LivenessEngineService livenessEngine;
    private PassiveScoringService passiveScorer;
    private ImageUtils imageUtils;
    private ThresholdCalibrationService service;

    @BeforeAll
    static void loadOpenCv() {
        // The proxy corpus renders and degrades images with real Mat operations.
        nu.pattern.OpenCV.loadLocally();
    }

    @BeforeEach
    void setUp() {
        frameEventRepo = mock(FrameEventRepository.class);
        configService = mock(ConfigService.class);
        livenessEngine = mock(LivenessEngineService.class);
        passiveScorer = mock(PassiveScoringService.class);
        imageUtils = mock(ImageUtils.class);

        when(configService.getEffectivePolicy(any())).thenReturn(
                new EffectivePolicy(true, true, 0.80, 0.50, 5, 7,
                        1, 3, 60_000L,
                        EnumSet.of(ChallengeType.BLINK), RepeatedFailureAction.LOCK_OUT,
                        -1.0, 30_000L, 1, 10, 0.6, 0.4));
        when(passiveScorer.scorerId()).thenReturn("test-scorer");
        when(passiveScorer.score(any(Mat.class), any(), any())).thenReturn(0.35);

        // Default: no detectable face in proxy frames -> honest "no attack data".
        when(livenessEngine.observeFace(any(Mat.class), any()))
                .thenReturn(new LivenessEngineService.FaceObservation(false, false, null, null));

        service = new ThresholdCalibrationService(frameEventRepo, configService,
                livenessEngine, passiveScorer, imageUtils);
    }

    // ---- window assembly ----

    @Test
    void decisionWindowsGroupScoresPerSessionInCaptureOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<FrameEvent> events = List.of(
                event(a, 0.9), event(a, 0.8),          // session A: 2 scores
                event(b, 0.4), event(b, 0.4), event(b, 0.4), event(b, 0.4), event(b, 0.4),
                event(b, 0.4), event(b, 0.4));         // session B: 7 scores

        List<List<Double>> windows = ThresholdCalibrationService.decisionWindows(events, 5);

        assertEquals(2, windows.size());
        assertEquals(List.of(0.9, 0.8), windows.get(0), "session A stays short (excluded later)");
        assertEquals(5, windows.get(1).size(), "session B truncated to the first decision window");
        assertEquals(0.4, windows.get(1).get(0), 1e-9);
    }

    @Test
    void decisionWindowsTolerateNullAndUnscoredEvents() {
        List<FrameEvent> events = new ArrayList<>();
        events.add(null);
        events.add(FrameEvent.builder().session(session()).stage(LivenessStage.PASSIVE).build());

        assertTrue(ThresholdCalibrationService.decisionWindows(events, 5).isEmpty());
    }

    // ---- end-to-end run ----

    @Test
    void recordedAttackSamplesArePreferredOverTheProxyCorpus() {
        when(frameEventRepo.findPassiveScoresForPassedSessions(LivenessStage.PASSIVE))
                .thenReturn(passedSessions(3, 0.90));
        when(frameEventRepo.findPassiveScoresForAttackSessions(LivenessStage.PASSIVE))
                .thenReturn(passedSessions(1, 0.30));

        ThresholdSweep.Result r = service.run(0.02);

        assertFalse(r.attackDataIsProxy());
        assertTrue(r.note().contains("recorded"), r.note());
        assertEquals(19, r.rows().size());
        assertEquals(3, r.rows().get(0).bonaFideWindows());
        assertEquals(1L, r.rows().get(0).attackWindows());
        assertEquals("test-scorer", r.scorer());
        assertNotNull(r.recommendedThreshold());
        // Attacks at 0.30, bona fide at 0.90 -> strictest safe point is 0.90.
        assertEquals(0.90, r.recommendedThreshold(), 1e-9);
    }

    @Test
    void noAttackSamplesFallsBackToProxyAndReportsHonestly() {
        when(frameEventRepo.findPassiveScoresForPassedSessions(LivenessStage.PASSIVE))
                .thenReturn(passedSessions(4, 0.90));
        when(frameEventRepo.findPassiveScoresForAttackSessions(LivenessStage.PASSIVE))
                .thenReturn(List.of());

        ThresholdSweep.Result r = service.run(0.02);

        assertEquals(4, r.rows().get(0).bonaFideWindows());
        if (r.attackDataIsProxy()) {
            assertTrue(r.note().contains("SIMULATED"), r.note());
        } else {
            // Proxy frames contained no detectable face -> APCER honestly unmeasured.
            assertTrue(r.note().contains("APCER unmeasured"), r.note());
        }
    }

    // ---- helpers ----

    private static LivenessSession session() {
        return LivenessSession.builder().id(UUID.randomUUID()).build();
    }

    private static FrameEvent event(UUID sessionId, double score) {
        LivenessSession s = LivenessSession.builder().id(sessionId).build();
        return FrameEvent.builder().session(s).stage(LivenessStage.PASSIVE)
                .livenessScore(score).build();
    }

    /** N sessions x 5 identical scores, in session-id order. */
    private static List<FrameEvent> passedSessions(int sessions, double score) {
        List<FrameEvent> out = new ArrayList<>();
        for (int s = 0; s < sessions; s++) {
            UUID id = UUID.randomUUID();
            for (int f = 0; f < 5; f++) out.add(event(id, score));
        }
        return out;
    }
}
