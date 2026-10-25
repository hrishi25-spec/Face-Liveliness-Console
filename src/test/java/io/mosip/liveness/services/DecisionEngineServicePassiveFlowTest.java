package io.mosip.liveness.services;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The passive stage of the HTTP decision path: median-window scoring with a
 * cold start, the challenge lock, and PAD fusion.
 *
 * <p>These behaviours replaced "compare this single frame's score with the
 * threshold", which let one blurry frame escalate a session, one lucky frame
 * pass it, and a late frame during an active challenge re-decide the session
 * while a challenge was still open.</p>
 */
class DecisionEngineServicePassiveFlowTest {

    private static final int MIN_FRAMES = 5;
    private static final int WINDOW_FRAMES = 7;
    private static final double THRESHOLD = 0.80;

    private LivenessEngineService livenessEngine;
    private PadEngineService padEngine;
    private ChallengeSelectorService challengeSelector;
    private ConfigService configService;
    private FrameEventRepository frameEventRepo;
    private ChallengeRepository challengeRepo;
    private AuditLogRepository auditLogRepo;
    private LivenessSessionRepository sessionRepo;
    private PassiveScoringService passiveScorer;

    private DecisionEngineService service;
    private ImageUtils imageUtils;
    private LivenessSession session;

    /** The window the mocked repository "persists" — grows by one per frame. */
    private final List<Double> persistedScores = new ArrayList<>();
    private double nextScore = 0.90;

    @BeforeEach
    void setUp() {
        livenessEngine = mock(LivenessEngineService.class);
        padEngine = mock(PadEngineService.class);
        challengeSelector = mock(ChallengeSelectorService.class);
        configService = mock(ConfigService.class);
        frameEventRepo = mock(FrameEventRepository.class);
        challengeRepo = mock(ChallengeRepository.class);
        auditLogRepo = mock(AuditLogRepository.class);
        sessionRepo = mock(LivenessSessionRepository.class);
        passiveScorer = mock(PassiveScoringService.class);
        imageUtils = mock(ImageUtils.class);

        when(configService.toCoreWorkflow(any())).thenReturn(WorkflowType.RESIDENT_REGISTRATION);
        when(configService.toDbChallenge(any())).thenReturn(
                io.mosip.liveness.models.enums.ChallengeType.BLINK);
        when(configService.getEffectivePolicy(any())).thenReturn(policy());
        when(challengeSelector.selectChallenge(anyList(), any())).thenReturn(
                io.mosip.liveness.models.enums.ChallengeType.BLINK);
        when(challengeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(challengeRepo.countBySessionId(any())).thenReturn(0L);
        when(challengeRepo.countBySessionIdAndStatus(any(), any())).thenReturn(0L);
        when(challengeRepo.findBySessionIdOrderByIssuedAtDesc(any())).thenReturn(List.of());
        when(challengeRepo.findFirstBySessionIdAndStatusOrderByIssuedAtDesc(any(), any()))
                .thenReturn(null);
        when(sessionRepo.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(padEngine.detect(any(Mat.class), any())).thenReturn(PadVerdict.bonaFide(0.9));
        when(passiveScorer.assessPad(any(), any())).thenReturn(Optional.empty());
        // Default: a PAD verdict is already confirmed by the preceding frames, so
        // attack tests can decide on one push. Tests that exercise the
        // confirmation gate override this.
        when(frameEventRepo.findRecentPadFlags(any(), any(), any()))
                .thenReturn(List.of(true, true, true));
        when(livenessEngine.observeFace(any(Mat.class), any()))
                .thenReturn(new LivenessEngineService.FaceObservation(
                        true, false, 0.85, new int[]{10, 10, 120, 140}));

        persistedScores.clear();
        nextScore = 0.90;
        when(passiveScorer.score(any(), any(), any())).thenReturn(nextScore);
        when(frameEventRepo.findScoresBySessionAndStage(any(), any()))
                .thenAnswer(inv -> new ArrayList<>(persistedScores));

        service = new DecisionEngineService(livenessEngine, padEngine, challengeSelector,
                configService, frameEventRepo, challengeRepo, auditLogRepo,
                sessionRepo, passiveScorer);

        session = LivenessSession.builder()
                .id(UUID.randomUUID())
                .workflowType(io.mosip.liveness.models.enums.WorkflowType.RESIDENT)
                .deviceId("WEB-CAM")
                .status(SessionStatus.ACTIVE)
                .currentStage(LivenessStage.PASSIVE)
                .retryCount(0)
                .online(true)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private EffectivePolicy policy() {
        return new EffectivePolicy(true, true, THRESHOLD, 0.50, MIN_FRAMES, WINDOW_FRAMES,
                1, 3, 60_000L,
                EnumSet.of(ChallengeType.BLINK), RepeatedFailureAction.LOCK_OUT,
                -1.0, 30_000L, 1, 10, 0.6, 0.4);
    }

    /**
     * Submits one frame at {@link #nextScore}. The score is appended to the
     * mocked window *before* the call, mirroring the production flush-then-query
     * inside the same transaction: the frame being decided counts itself.
     */
    private FrameProcessResult pushFrame() {
        when(passiveScorer.score(any(), any(), any())).thenReturn(nextScore);
        persistedScores.add(nextScore);
        return service.processFrame(session, mock(Mat.class), imageUtils);
    }

    // ---- cold start ----

    @Test
    void noDecisionUntilMinFramesHaveAccumulated() {
        nextScore = 0.90;
        for (int frame = 1; frame < MIN_FRAMES; frame++) {
            FrameProcessResult r = pushFrame();
            assertEquals("retry_passive", r.getAction(), "frame " + frame);
            assertEquals("Checking face liveness...", r.getMessage());
            assertEquals(LivenessStage.PASSIVE, session.getCurrentStage());
            assertEquals(SessionStatus.ACTIVE, session.getStatus());
            verify(challengeRepo, never()).save(any());
        }

        FrameProcessResult fifth = pushFrame();   // window now warm
        assertEquals("proceed", fifth.getAction());
        assertEquals(SessionStatus.PASSED, session.getStatus());
        assertEquals(LivenessStage.COMPLETED, session.getCurrentStage());
    }

    @Test
    void oneBlurryEarlyFrameCannotEscalateTheSession() {
        // Three good frames, then a dim one: still inside the cold start, and
        // even at minFrames the median (0.85) clears the threshold.
        nextScore = 0.90;
        pushFrame();
        pushFrame();
        pushFrame();
        nextScore = 0.10;                 // motion blur / exposure hunt
        FrameProcessResult fourth = pushFrame();
        assertEquals("retry_passive", fourth.getAction());
        assertEquals(LivenessStage.PASSIVE, session.getCurrentStage());

        nextScore = 0.90;
        FrameProcessResult fifth = pushFrame();
        assertEquals("proceed", fifth.getAction(),
                "median of [0.9,0.9,0.9,0.1,0.9] = 0.9 must pass; the mean would not");
    }

    @Test
    void medianBelowThresholdEscalatesAndIssuesOneChallenge() {
        nextScore = 0.30;
        for (int i = 0; i < MIN_FRAMES; i++) {
            FrameProcessResult r = pushFrame();
            if (i < MIN_FRAMES - 1) {
                assertEquals("retry_passive", r.getAction());
            } else {
                assertEquals("escalate_to_active", r.getAction());
                assertNotNull(r.getChallenge());
                assertEquals("BLINK", r.getChallenge().getChallengeType());
                assertEquals(1, r.getChallenge().getAttemptNumber());
                assertEquals(60_000, r.getChallenge().getTimeoutMs());
            }
        }
        assertEquals(LivenessStage.ACTIVE, session.getCurrentStage());
        assertEquals(SessionStatus.ACTIVE, session.getStatus(), "escalation must not fail the session");
        verify(challengeRepo).save(any());
    }

    // ---- challenge lock ----

    @Test
    void lateHighScoreFrameDuringActiveCannotPassTheSession() {
        ChallengeEntity open = challenge(ChallengeStatus.ISSUED);
        when(challengeRepo.findFirstBySessionIdAndStatusOrderByIssuedAtDesc(
                session.getId(), ChallengeStatus.ISSUED)).thenReturn(open);
        session.setCurrentStage(LivenessStage.ACTIVE);
        persistedScores.clear();

        nextScore = 0.99;                 // a *great* frame arrives late
        FrameProcessResult r = pushFrame();

        assertEquals("escalate_to_active", r.getAction(),
                "the open challenge must be re-served, not a pass");
        assertEquals(open.getId(), r.getChallenge().getChallengeId());
        assertEquals(SessionStatus.ACTIVE, session.getStatus(),
                "a passive score must never complete a session with an open challenge");
        assertEquals(LivenessStage.ACTIVE, session.getCurrentStage());
        verify(challengeRepo, never()).save(any());   // no duplicate challenge
        // The frame was not even scored: passive scoring stops at the lock.
        verify(passiveScorer, never()).score(any(), any(), any());
    }

    @Test
    void activeStageWithoutAnOpenChallengeSelfHealsByIssuingOne() {
        session.setCurrentStage(LivenessStage.ACTIVE);
        when(challengeRepo.findFirstBySessionIdAndStatusOrderByIssuedAtDesc(
                session.getId(), ChallengeStatus.ISSUED)).thenReturn(null);

        FrameProcessResult r = service.processFrame(session, mock(Mat.class), imageUtils);

        assertEquals("escalate_to_active", r.getAction());
        assertNotNull(r.getChallenge(), "self-heal: an active stage must always end up with a challenge");
        verify(challengeRepo).save(any());
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
    }

    // ---- PAD fusion ----

    @Test
    void modelPadAttackEscalatesToTheChallengeInsteadOfRejecting() {
        when(passiveScorer.assessPad(any(), any()))
                .thenReturn(Optional.of(PadVerdict.attack(PadAttackType.SCREEN_REPLAY, 0.93)));

        FrameProcessResult r = pushFrame();

        assertEquals("escalate_to_active", r.getAction(),
                "a confirmed PAD hit must hand the verdict to the active challenge");
        assertNotNull(r.getChallenge(), "the escalation must carry a challenge");
        assertEquals(LivenessStage.ACTIVE, session.getCurrentStage());
        // The verdict travels with the escalation: the screen that ran the check
        // lists the attack instead of showing a silently clean frame.
        assertTrue((Boolean) r.getPadFlag(), "the escalation must carry the PAD flag");
        assertEquals("SCREEN_REPLAY", r.getPadAttackType(),
                "a sharp frame the texture rule cannot see is named by the model");
        // The hit is also recorded on the session's trail.
        verify(auditLogRepo, org.mockito.Mockito.atLeastOnce()).save(
                org.mockito.ArgumentMatchers.argThat(a ->
                        "PAD_FLAGGED".equals(a.getEventType())));
        assertEquals(SessionStatus.ACTIVE, session.getStatus(),
                "the session stays open for the challenge");
        assertNull(session.getFailureReason());
        verify(challengeRepo).save(any());
    }

    @Test
    void aFlatPresentationStaysAPrintEvenWhenTheModelCallsItReplay() {
        // MiniFASNet labels a printed photo through its replay class (its print
        // class measures 0.0000 on the fixture), so its type cannot tell the two
        // presentations apart. The texture rule can: a flat frame is a print.
        when(padEngine.detect(any(Mat.class), any()))
                .thenReturn(PadVerdict.attack(PadAttackType.PRINTED_PHOTO, 0.99));
        when(passiveScorer.assessPad(any(), any()))
                .thenReturn(Optional.of(PadVerdict.attack(PadAttackType.SCREEN_REPLAY, 0.93)));

        FrameProcessResult r = pushFrame();

        assertEquals("escalate_to_active", r.getAction());
        assertEquals("PRINTED_PHOTO", r.getPadAttackType(),
                "the print evidence, not the model's replay label, names a flat presentation");
    }

    @Test
    void heuristicPadAttackAlsoEscalatesBeforeScoring() {
        when(padEngine.detect(any(Mat.class), any()))
                .thenReturn(PadVerdict.attack(PadAttackType.PRINTED_PHOTO, 0.99));

        FrameProcessResult r = pushFrame();

        assertEquals("escalate_to_active", r.getAction());
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
        verify(passiveScorer, never()).score(any(), any(), any());
    }

    @Test
    void confirmedPadWithActiveLivenessDisabledStillRejects() {
        // No challenge stage exists to hand the verdict to, so the passive
        // stage's attack decision is final.
        when(configService.getEffectivePolicy(any())).thenReturn(
                new EffectivePolicy(true, false, THRESHOLD, 0.50, MIN_FRAMES, WINDOW_FRAMES,
                        1, 3, 60_000L,
                        EnumSet.of(ChallengeType.BLINK), RepeatedFailureAction.LOCK_OUT,
                        -1.0, 30_000L, 1, 10, 0.6, 0.4));
        when(passiveScorer.assessPad(any(), any()))
                .thenReturn(Optional.of(PadVerdict.attack(PadAttackType.SCREEN_REPLAY, 0.93)));

        FrameProcessResult r = pushFrame();

        assertEquals("reject", r.getAction());
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals(LivenessStage.COMPLETED, session.getCurrentStage());
        assertEquals("presentation_attack:SCREEN_REPLAY", session.getFailureReason());
    }

    @Test
    void aSinglePadFrameDoesNotDecideUntilTheVerdictPersists() {
        // The model flipped to an attack class on this frame, but the preceding
        // frames were clean: an isolated flip must not move the session at all.
        when(passiveScorer.assessPad(any(), any()))
                .thenReturn(Optional.of(PadVerdict.attack(PadAttackType.SCREEN_REPLAY, 0.75)));
        when(frameEventRepo.findRecentPadFlags(any(), any(), any()))
                .thenReturn(List.of(true, true, false));

        FrameProcessResult r = pushFrame();

        assertEquals("retry_passive", r.getAction());
        assertTrue((Boolean) r.getPadFlag());
        assertEquals(SessionStatus.ACTIVE, session.getStatus(),
                "an unconfirmed PAD frame must not fail the session");
        assertNull(session.getFailureReason());
        verify(auditLogRepo, never()).save(any());
    }

    // ---- scoring source ----

    @Test
    void passiveScoreComesFromTheScorerNotTheQualityHeuristic() {
        nextScore = 0.90;
        for (int i = 0; i < MIN_FRAMES; i++) pushFrame();

        verify(passiveScorer, org.mockito.Mockito.times(MIN_FRAMES)).score(any(), any(), any());
        verify(livenessEngine, never()).scorePassive(any(), any(), any());
    }

    @Test
    void activeDisabledFailsInsteadOfEscalating() {
        when(configService.getEffectivePolicy(any())).thenReturn(
                new EffectivePolicy(true, false, THRESHOLD, 0.50, MIN_FRAMES, WINDOW_FRAMES,
                        1, 3, 60_000L,
                        EnumSet.of(ChallengeType.BLINK), RepeatedFailureAction.LOCK_OUT,
                        -1.0, 30_000L, 1, 10, 0.6, 0.4));
        nextScore = 0.20;

        FrameProcessResult r = pushFrame();
        for (int i = 1; i < MIN_FRAMES; i++) r = pushFrame();

        assertEquals("reject", r.getAction());
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals("passive_liveness_below_threshold", session.getFailureReason());
        assertNull(r.getChallenge());
        assertNotEquals(LivenessStage.ACTIVE, session.getCurrentStage());
    }

    // ---- frozen policy snapshot & disabled liveness (V4) ----

    /** A workflow with liveness disabled but PAD still enforced. */
    private EffectivePolicy livenessDisabledPolicy() {
        return new EffectivePolicy(false, true, THRESHOLD, 0.50, MIN_FRAMES, WINDOW_FRAMES,
                1, 3, 60_000L, EnumSet.of(ChallengeType.BLINK), RepeatedFailureAction.LOCK_OUT,
                -1.0, 30_000L, 1, 10, 0.6, 0.4);
    }

    @Test
    void frozenSnapshotGovernsTheSessionEvenWhenLiveConfigDiffers() {
        // Live config says 0.80 (0.90 would pass); the frozen snapshot says 0.95.
        session.setPolicySnapshot(new EffectivePolicy(true, true, 0.95, 0.50,
                MIN_FRAMES, WINDOW_FRAMES, 1, 3, 60_000L,
                EnumSet.of(ChallengeType.BLINK), RepeatedFailureAction.LOCK_OUT,
                -1.0, 30_000L, 1, 10, 0.6, 0.4));
        nextScore = 0.90;

        FrameProcessResult r = pushFrame();
        for (int i = 1; i < MIN_FRAMES; i++) r = pushFrame();

        assertEquals("escalate_to_active", r.getAction(),
                "the snapshot threshold (0.95), not the live one (0.80), must decide");
    }

    @Test
    void disabledLivenessPassesWithoutScoring() {
        when(configService.getEffectivePolicy(any())).thenReturn(livenessDisabledPolicy());
        nextScore = 0.01;   // would fail any liveness threshold

        FrameProcessResult r = pushFrame();

        assertEquals("proceed", r.getAction());
        assertEquals(SessionStatus.PASSED, session.getStatus());
        assertEquals(LivenessStage.COMPLETED, session.getCurrentStage());
        verify(passiveScorer, never()).score(any(), any(), any());
    }

    @Test
    void disabledLivenessStillRejectsAConfirmedPresentationAttack() {
        when(configService.getEffectivePolicy(any())).thenReturn(livenessDisabledPolicy());
        when(padEngine.detect(any(Mat.class), any()))
                .thenReturn(PadVerdict.attack(PadAttackType.SCREEN_REPLAY, 0.97));

        FrameProcessResult r = pushFrame();

        assertEquals("reject", r.getAction(),
                "disabling liveness must never disable PAD");
        assertEquals(SessionStatus.FAILED, session.getStatus());
    }

    private ChallengeEntity challenge(ChallengeStatus status) {
        return ChallengeEntity.builder()
                .id(UUID.randomUUID())
                .session(session)
                .challengeType(io.mosip.liveness.models.enums.ChallengeType.BLINK)
                .status(status)
                .attemptNumber(1)
                .timeoutMs(60_000)
                .issuedAt(OffsetDateTime.now())
                .build();
    }
}
