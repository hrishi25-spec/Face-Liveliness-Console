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
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencv.core.Mat;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Patience semantics for active challenges.
 *
 * <p>A challenge must stay open for a whole window (15s floor) while every
 * submitted burst is evaluated, passing the moment the action is recognised. A
 * miss only counts as a failure once the window has elapsed. Previously a single
 * short burst decided the outcome and burned a retry, so a person who simply
 * needed a moment longer failed the check.</p>
 */
class DecisionEngineServiceTest {

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
    private ChallengeEntity challenge;
    private List<Mat> frames;

    private static final io.mosip.liveness.models.enums.ChallengeType DB_TURN_LEFT =
            io.mosip.liveness.models.enums.ChallengeType.TURN_LEFT;

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

        // Default: the session row lock finds nothing (detached test session),
        // so the service falls back to the instance passed in.
        when(sessionRepo.findByIdForUpdate(any())).thenReturn(java.util.Optional.empty());

        when(configService.toCoreWorkflow(any())).thenReturn(WorkflowType.RESIDENT_REGISTRATION);
        when(configService.toDbChallenge(any())).thenReturn(DB_TURN_LEFT);
        when(challengeSelector.selectChallenge(anyList(), any())).thenReturn(DB_TURN_LEFT);
        when(padEngine.detect(any(Mat.class), any())).thenReturn(PadVerdict.bonaFide(0.9));
        // Challenge validation observes each frame and ORs the model PAD opinion
        // with the heuristic one; these tests pin the challenge timing and the
        // heuristic path, so the model has no opinion unless a test says so.
        when(livenessEngine.observeFace(any(Mat.class), any()))
                .thenReturn(new LivenessEngineService.FaceObservation(
                        true, false, 0.9, new int[]{0, 0, 10, 10}));
        when(passiveScorer.assessPad(any(Mat.class), any())).thenReturn(java.util.Optional.empty());
        when(challengeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(challengeRepo.countBySessionIdAndStatus(any(), any())).thenReturn(0L);
        when(challengeRepo.countBySessionId(any())).thenReturn(0L);

        service = new DecisionEngineService(livenessEngine, padEngine, challengeSelector,
                configService, frameEventRepo, challengeRepo, auditLogRepo,
                sessionRepo, passiveScorer);

        session = LivenessSession.builder()
                .id(UUID.randomUUID()).workflowType(io.mosip.liveness.models.enums.WorkflowType.RESIDENT)
                .deviceId("WEB-CAM").status(SessionStatus.ACTIVE).currentStage(LivenessStage.ACTIVE)
                .retryCount(0).online(true)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();

        challenge = ChallengeEntity.builder()
                .id(UUID.randomUUID()).session(session)
                .challengeType(DB_TURN_LEFT).status(ChallengeStatus.ISSUED)
                .attemptNumber(1).timeoutMs(60_000)
                .issuedAt(OffsetDateTime.now())
                .build();

        frames = List.of(mock(Mat.class), mock(Mat.class));
    }

    private EffectivePolicy policy(int minChallengeCount, int maxRetries) {
        return policy(minChallengeCount, maxRetries, RepeatedFailureAction.LOCK_OUT);
    }

    private EffectivePolicy policy(int minChallengeCount, int maxRetries,
                                   RepeatedFailureAction onRepeatedFailure) {
        return new EffectivePolicy(true, true, 0.80, 0.50, 5, 7,
                minChallengeCount, maxRetries, 60_000L,
                EnumSet.of(ChallengeType.TURN_HEAD_LEFT), onRepeatedFailure,
                -1.0, 30_000L, 1, 10, 0.6, 0.4);
    }

    /**
     * The stage that decides one of the three terminal signals: the window has
     * elapsed without the action, so the miss is real and burns the last retry.
     */
    private Map<String, Object> exhaustTheRetryBudget(RepeatedFailureAction onRepeatedFailure) {
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(61));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 1, onRepeatedFailure));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);
        return service.processChallengeValidation(session, challenge, frames, imageUtils);
    }

    @Test
    void aMissInsideTheWindowKeepsTheSameChallengeOpen() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("continue", result.get("action"));
        assertFalse((Boolean) result.get("passed"));
        assertEquals(ChallengeStatus.ISSUED, challenge.getStatus(), "challenge must stay open");
        assertNull(challenge.getCompletedAt());
        assertEquals(0, session.getRetryCount(), "a poll must not burn retry budget");
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
        verify(challengeRepo, never()).save(any());
    }

    @Test
    void aShortConfiguredTimeoutStillHonoursTheFifteenSecondFloor() {
        // Legacy DB row with an 8s timeout, 10s in: still inside the 15s floor.
        challenge.setTimeoutMs(8_000);
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(10));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("continue", result.get("action"));
        assertEquals(ChallengeStatus.ISSUED, challenge.getStatus());
        assertEquals(0, session.getRetryCount());
    }

    @Test
    void aConfiguredFloorIsHonouredSoTheTimeoutPathRunsInSeconds() {
        // Test-only lowering of the floor (production keeps 15s). A stored 1000ms
        // timeout issued 5s ago must still count as elapsed, but only because the
        // floor is 2000ms.
        ReflectionTestUtils.setField(service, "minChallengeWindowMs", 2_000L);
        challenge.setTimeoutMs(1_000);
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(5));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("retry_challenge", result.get("action"));
        assertEquals(1, session.getRetryCount());
    }

    @Test
    void aConfiguredFloorKeepsAChallengeOpenPastItsStoredTimeout() {
        // Opposite direction: floor 5000ms overrides a stored 1000ms window that
        // expired 3s ago, so the miss must not be charged yet.
        ReflectionTestUtils.setField(service, "minChallengeWindowMs", 5_000L);
        challenge.setTimeoutMs(1_000);
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(3));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("continue", result.get("action"));
        assertEquals(ChallengeStatus.ISSUED, challenge.getStatus());
        assertEquals(0, session.getRetryCount());
    }

    @Test
    void aFloorBelowTheAbsoluteMinimumIsClampedToOneSecond() {
        // The configured 10ms floor is clamped to one second. Keep the request
        // near the start of that window so scheduler delays cannot make the test
        // cross the deadline before the assertion.
        ReflectionTestUtils.setField(service, "minChallengeWindowMs", 10L);
        challenge.setTimeoutMs(10);
        challenge.setIssuedAt(OffsetDateTime.now().minus(100, java.time.temporal.ChronoUnit.MILLIS));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("continue", result.get("action"));
        assertEquals(0, session.getRetryCount());
    }

    @Test
    void aMissAfterTheWindowElapsesCountsAndIssuesTheNextChallenge() {
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(61));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("retry_challenge", result.get("action"));
        assertEquals(ChallengeStatus.FAILED, challenge.getStatus());
        assertNotNull(challenge.getCompletedAt());
        assertEquals(1, session.getRetryCount());
        assertNotNull(result.get("challenge"), "a fresh challenge must be issued");
    }

    @Test
    void repeatedPollsInsideTheWindowNeverExhaustTheRetryBudget() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        for (int i = 0; i < 20; i++) {
            Map<String, Object> result =
                    service.processChallengeValidation(session, challenge, frames, imageUtils);
            assertEquals("continue", result.get("action"), "poll " + i + " should stay open");
        }

        assertEquals(0, session.getRetryCount());
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
    }

    @Test
    void aSpentBudgetOnALockOutWorkflowLocksTheSession() {
        Map<String, Object> result = exhaustTheRetryBudget(RepeatedFailureAction.LOCK_OUT);

        assertEquals("locked", result.get("action"));
        assertEquals(Boolean.FALSE, result.get("mayRetrySession"));
        assertEquals("max_retries_exceeded:locked_out", session.getFailureReason());
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals(LivenessStage.COMPLETED, session.getCurrentStage());
        assertNull(result.get("challenge"), "a terminal outcome issues no further challenge");
    }

    @Test
    void aSpentBudgetOnAFallbackWorkflowFailsButAllowsANewSession() {
        Map<String, Object> result = exhaustTheRetryBudget(RepeatedFailureAction.FALLBACK);

        assertEquals("failed", result.get("action"));
        assertEquals(Boolean.TRUE, result.get("mayRetrySession"));
        // The reason keeps the prefix every existing client already recognises.
        assertEquals("max_retries_exceeded", session.getFailureReason());
        assertEquals(SessionStatus.FAILED, session.getStatus());
    }

    @Test
    void aSpentBudgetOnAnEscalatingWorkflowRoutesToAnOperator() {
        Map<String, Object> result =
                exhaustTheRetryBudget(RepeatedFailureAction.ESCALATE_TO_OPERATOR);

        assertEquals("escalate_to_operator", result.get("action"));
        assertEquals(Boolean.FALSE, result.get("mayRetrySession"));
        assertEquals("max_retries_exceeded:escalation_required", session.getFailureReason());
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals(LivenessStage.COMPLETED, session.getCurrentStage());
    }

    @Test
    void passingStopsTheChallengeImmediately() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(true);
        // The repository query flushes the just-passed challenge, so this pass is
        // the one counted towards minChallengeCount.
        when(challengeRepo.countBySessionIdAndStatus(any(), any())).thenReturn(1L);

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("proceed", result.get("action"));
        assertTrue((Boolean) result.get("passed"));
        assertEquals(ChallengeStatus.PASSED, challenge.getStatus());
        assertNotNull(challenge.getCompletedAt());
        assertEquals(SessionStatus.PASSED, session.getStatus());
        assertEquals(0, session.getRetryCount());
    }

    @Test
    void twoChallengeWorkflowNeedsASecondChallengeBeforeProceeding() {
        // Supervisor default: minChallengeCount = 2. The first challenge passing
        // must ask for another, not complete the session.
        when(configService.getEffectivePolicy(any())).thenReturn(policy(2, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(true);
        when(challengeRepo.countBySessionIdAndStatus(any(), any())).thenReturn(1L);

        Map<String, Object> result =
                service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("retry_challenge", result.get("action"));
        assertNotNull(result.get("challenge"), "a second challenge must be issued");
        assertNotEquals(SessionStatus.PASSED, session.getStatus());
    }

    @Test
    void aPresentationAttackDuringAnAttemptStillRejectsImmediately() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(padEngine.detect(any(Mat.class), any()))
                .thenReturn(PadVerdict.attack(PadAttackType.PRINTED_PHOTO, 0.9));

        Map<String, Object> result = service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("reject", result.get("action"));
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals(ChallengeStatus.FAILED, challenge.getStatus());
        assertEquals(Boolean.TRUE, result.get("padFlag"));
        assertEquals("PRINTED_PHOTO", result.get("padAttackType"));
    }

    @Test
    void aModelPadHitDuringAnAttemptRejectsEvenWhenTheHeuristicIsClean() {
        // The heuristic cannot see a well-lit phone screen; the model can. A
        // replay that was already flagged on the passive stage must not be able
        // to perform the blink and leave with PASSED.
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(passiveScorer.assessPad(any(Mat.class), any()))
                .thenReturn(java.util.Optional.of(
                        PadVerdict.attack(PadAttackType.SCREEN_REPLAY, 0.97)));

        Map<String, Object> result =
                service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("reject", result.get("action"));
        assertEquals(Boolean.TRUE, result.get("padFlag"));
        assertEquals("SCREEN_REPLAY", result.get("padAttackType"));
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals("presentation_attack:SCREEN_REPLAY", session.getFailureReason());
        verify(livenessEngine, never()).validateActive(any(), anyList(), any());
    }

    @Test
    void aSingleModelFlipInsideTheBurstDoesNotRejectThePerson() {
        // One flagged frame in a live burst is the same ranked noise the passive
        // stage refuses to act on; only a consistent replay fails the check.
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(passiveScorer.assessPad(any(Mat.class), any()))
                .thenReturn(java.util.Optional.of(
                                PadVerdict.attack(PadAttackType.SCREEN_REPLAY, 0.97)),
                        java.util.Optional.empty(),
                        java.util.Optional.empty());
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result = service.processChallengeValidation(session, challenge,
                List.of(mock(Mat.class), mock(Mat.class), mock(Mat.class)), imageUtils);

        assertEquals("continue", result.get("action"),
                "a single flip must leave the challenge open, not fail it");
        assertEquals(SessionStatus.ACTIVE, session.getStatus());
        assertEquals(ChallengeStatus.ISSUED, challenge.getStatus());
        assertNull(result.get("padAttackType"), "no PAD verdict was reached");
    }

    // -------------------------------------------------- manual extra check
    //
    // Active liveness is normally reached only by the passive-to-active
    // escalation. A passed session may ask for one challenge anyway
    // (POST /api/v1/sessions/{id}/active-liveness): the challenge is marked
    // manual, and its verdict is recorded on the row and in the audit trail
    // without ever rewriting the session's verdict — the passive pass stands,
    // including when the extra check sees an attack.

    /** The state a session is in when the manual extra check is requested. */
    private void passedSession() {
        session.setStatus(SessionStatus.PASSED);
        session.setCurrentStage(LivenessStage.COMPLETED);
        session.setFinalResult(true);
    }

    @Test
    void manualCheckIsRefusedOnASessionThatHasNotPassed() {
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.startActiveLiveness(session));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(challengeRepo, never()).save(any());
    }

    @Test
    void manualCheckIssuesAChallengeMarkedManualOnAPassedSession() {
        passedSession();
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));

        ChallengeEntity issued = service.startActiveLiveness(session);

        assertTrue(Boolean.TRUE.equals(issued.getManual()),
                "the extra check must be distinguishable from an escalation challenge");
        assertEquals(ChallengeStatus.ISSUED, issued.getStatus());
        assertEquals(SessionStatus.PASSED, session.getStatus(), "the pass is not undone");
        verify(challengeRepo).save(issued);
    }

    @Test
    void manualCheckReusesTheChallengeThatIsAlreadyOpen() {
        passedSession();
        ChallengeEntity open = ChallengeEntity.builder()
                .id(UUID.randomUUID()).session(session)
                .challengeType(DB_TURN_LEFT).status(ChallengeStatus.ISSUED)
                .attemptNumber(1).timeoutMs(60_000).manual(true)
                .issuedAt(OffsetDateTime.now())
                .build();
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(challengeRepo.findFirstBySessionIdAndStatusOrderByIssuedAtDesc(any(), any()))
                .thenReturn(open);

        assertEquals(open, service.startActiveLiveness(session));
        verify(challengeRepo, never()).save(any());
    }

    @Test
    void manualCheckIsRefusedWhenActiveLivenessIsDisabled() {
        passedSession();
        when(configService.getEffectivePolicy(any())).thenReturn(
                new EffectivePolicy(true, false, 0.80, 0.50, 5, 7, 1, 2, 60_000L,
                        EnumSet.of(ChallengeType.TURN_HEAD_LEFT), RepeatedFailureAction.LOCK_OUT,
                        -1.0, 30_000L, 1, 10, 0.6, 0.4));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.startActiveLiveness(session));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(challengeRepo, never()).save(any());
    }

    @Test
    void aPassedManualCheckIsRecordedWithoutRewritingTheSession() {
        passedSession();
        challenge.setManual(true);
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(true);

        Map<String, Object> result =
                service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("manual_check_passed", result.get("action"));
        assertTrue((Boolean) result.get("passed"));
        assertEquals(ChallengeStatus.PASSED, challenge.getStatus());
        assertNotNull(challenge.getCompletedAt());
        assertEquals(SessionStatus.PASSED, session.getStatus());
        assertEquals(Boolean.TRUE, session.getFinalResult());
        assertEquals(0, session.getRetryCount(), "an extra check must not consume retry budget");
    }

    @Test
    void aMissedManualCheckLeavesTheSessionPassed() {
        passedSession();
        challenge.setManual(true);
        challenge.setIssuedAt(OffsetDateTime.now().minusSeconds(61));
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(livenessEngine.validateActive(eq(DB_TURN_LEFT), anyList(), any())).thenReturn(false);

        Map<String, Object> result =
                service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("manual_check_failed", result.get("action"));
        assertFalse((Boolean) result.get("passed"));
        assertEquals(ChallengeStatus.FAILED, challenge.getStatus());
        assertNotNull(challenge.getCompletedAt());
        assertEquals(SessionStatus.PASSED, session.getStatus(),
                "a missed extra check must not rewrite the passive pass");
        assertEquals(0, session.getRetryCount());
        verify(challengeRepo, never()).save(any());   // and no follow-up challenge is issued
    }

    @Test
    void aPadHitDuringAManualCheckIsAuditedWithoutRewritingTheSession() {
        passedSession();
        challenge.setManual(true);
        when(configService.getEffectivePolicy(any())).thenReturn(policy(1, 2));
        when(padEngine.detect(any(Mat.class), any()))
                .thenReturn(PadVerdict.attack(PadAttackType.PRINTED_PHOTO, 0.9));

        Map<String, Object> result =
                service.processChallengeValidation(session, challenge, frames, imageUtils);

        assertEquals("manual_check_failed", result.get("action"));
        assertEquals(ChallengeStatus.FAILED, challenge.getStatus());
        assertEquals(SessionStatus.PASSED, session.getStatus(),
                "the extra check is recorded; the verdict it was asked about is not rewritten");

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo, atLeastOnce()).save(saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(e ->
                        "PAD_REJECTED".equals(e.getEventType())
                                && "active_manual".equals(e.getDetails().get("stage"))),
                "the attack is recorded against the manual check, not against the session");
    }
}
