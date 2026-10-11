package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.mosip.liveness.android.LivenessFailCategory;
import io.mosip.liveness.android.LivenessFinalResult;
import io.mosip.liveness.android.LivenessHint;
import io.mosip.liveness.android.LivenessState;
import io.mosip.liveness.android.LivenessStateEvent;

/**
 * The service-mediated half of the design's state→UI contract: every REST
 * response the client can receive, mapped to the events the overlay renders.
 */
class ServiceLivenessEventMapperTest {

    private static final String SESSION = "11111111-2222-3333-4444-555555555555";
    private static final String CHALLENGE_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    // ------------------------------------------------------------ session start

    @Test
    void startedRendersTheSameWarmUpAsTheOrchestrator() {
        ServiceLivenessEventMapper.Delivery d =
                ServiceLivenessEventMapper.started(context());
        assertEquals(2, d.states().size(), "INITIALIZING then POSITIONING, like the in-process gate");
        assertEquals(LivenessState.INITIALIZING, d.states().get(0).state());
        assertEquals("liveness.checking", d.states().get(0).uiMessageKey());
        assertEquals(LivenessState.POSITIONING, d.states().get(1).state());
        assertEquals("liveness.hint.look_camera", d.states().get(1).uiMessageKey());
        assertFalse(d.ended(), "starting a session is not a verdict");
    }

    // ------------------------------------------------------------- frame states

    @Test
    void aFrameWithoutAFacePositionsTheUser() {
        LivenessStateEvent event = only(ServiceLivenessEventMapper.fromFrame(
                frame("retry_passive", null, false, false, false, null), context(), null));
        assertEquals(LivenessState.POSITIONING, event.state());
        assertEquals(LivenessHint.NO_FACE, event.hint(), "the design's generic look-at-the-camera hint");
        assertNull(event.uiMessageKey(), "with a hint present the presenter resolves the hint's key");
        assertNull(event.challenge());
    }

    @Test
    void aFrameWithSeveralFacesPositionsTheUser() {
        LivenessStateEvent event = only(ServiceLivenessEventMapper.fromFrame(
                frame("retry_passive", null, true, true, false, null), context(), null));
        assertEquals(LivenessState.POSITIONING, event.state());
        assertEquals(LivenessHint.MULTIPLE_FACES, event.hint());
        assertEquals("liveness.hint.single_person", event.hint().uiMessageKey());
    }

    @Test
    void aScoringFrameShowsCheckingLiveness() {
        LivenessStateEvent event = only(ServiceLivenessEventMapper.fromFrame(
                frame("retry_passive", null, true, false, false, null), context(), null));
        assertEquals(LivenessState.PASSIVE_EVALUATING, event.state());
        assertEquals("liveness.checking", event.uiMessageKey());
        assertNull(event.progress(), "the service reports no combined score before it decides");
    }

    @Test
    void anEscalationPromptsTheIssuedChallengeWithItsCounter() {
        LivenessStateEvent event = only(ServiceLivenessEventMapper.fromFrame(
                frame("escalate_to_active", challenge("BLINK"), true, false, false, null),
                context(), null));
        assertEquals(LivenessState.CHALLENGE_PROMPT, event.state());
        assertEquals("BLINK", event.challenge(),
                "the presenter derives the prompt key from the challenge type");
        assertEquals(1, event.challengeIndex());
        assertEquals(2, event.challengeTotal(), "the frozen policy's minChallengeCount");
        assertNull(event.uiMessageKey(), "no raw prompt text crosses this boundary");
    }

    @Test
    void unknownOrMissingActionsFailSafe() {
        for (LivenessClient.FrameResult result : new LivenessClient.FrameResult[]{
                null,
                frame(null, null, true, false, false, null),
                frame("something_new", null, true, false, false, null)}) {
            ServiceLivenessEventMapper.Delivery d =
                    ServiceLivenessEventMapper.fromFrame(result, context(), null);
            assertEquals(LivenessState.PASSIVE_EVALUATING, only(d).state(),
                    "an unrecognised response keeps scoring rather than ending the session");
            assertFalse(d.ended());
        }
    }

    // ----------------------------------------------------------------- verdicts

    @Test
    void proceedPassesTheSession() {
        ServiceLivenessEventMapper.Delivery d = ServiceLivenessEventMapper.fromFrame(
                frame("proceed", null, true, false, false, null), context(), null);
        assertEquals(LivenessState.PASSED, only(d).state());
        assertEquals("liveness.success", only(d).uiMessageKey());
        assertEquals(1.0, only(d).progress());
        assertEquals(LivenessFinalResult.LivenessOutcome.PASSED, d.finalResult().outcome());
        assertEquals(LivenessFinalResult.NextAction.PROCEED, d.finalResult().nextAction());
        assertEquals(SESSION, d.finalResult().sessionId());
        assertEquals(0, d.finalResult().validForSeconds(),
                "the REST contract carries no gate-validity window");
    }

    @Test
    void aPadRejectionStaysGenericAndRetryable() {
        ServiceLivenessEventMapper.Delivery flagged = ServiceLivenessEventMapper.fromFrame(
                frame("reject", null, true, false, true, "PRINT"), context(), null);
        assertEquals("liveness.pad.generic", only(flagged).uiMessageKey(),
                "a PAD verdict never exposes its detail (design §4)");
        assertEquals(LivenessState.ATTEMPT_FAILED, only(flagged).state());
        assertFalse(flagged.ended(), "a rejection the user can retry is not a terminal session end");
        assertEquals(LivenessFailCategory.GENERIC, only(flagged).failCategory());

        ServiceLivenessEventMapper.Delivery fromReason = ServiceLivenessEventMapper.fromFrame(
                frame("reject", null, true, false, false, null), context(),
                "presentation_attack:FFT");
        assertEquals("liveness.pad.generic", only(fromReason).uiMessageKey(),
                "the closed session's failureReason identifies the attack when the flag is absent");
    }

    @Test
    void aPlainRejectionIsRetryable() {
        ServiceLivenessEventMapper.Delivery d = ServiceLivenessEventMapper.fromFrame(
                frame("reject", null, true, false, false, null), context(), "passive_liveness_below_threshold");
        assertEquals(LivenessState.ATTEMPT_FAILED, only(d).state());
        assertEquals("liveness.failed.try_again", only(d).uiMessageKey());
        assertFalse(d.ended());
    }

    @Test
    void anExhaustedBudgetEndsTheSessionWithTheFrozenFailureAction() {
        ServiceLivenessEventMapper.Delivery lockOut = ServiceLivenessEventMapper.fromFrame(
                frame("reject", null, true, false, false, null), withAction("LOCK_OUT"),
                "max_retries_exceeded");
        assertEquals(LivenessState.TERMINAL_FAILURE, only(lockOut).state());
        assertEquals("liveness.max_retries.recovery", only(lockOut).uiMessageKey(),
                "budget spent → recovery guidance, never a retry button (design §4)");
        assertEquals(LivenessFailCategory.MAX_RETRIES, only(lockOut).failCategory());
        assertEquals(LivenessFinalResult.NextAction.LOCKOUT, lockOut.finalResult().nextAction());
        assertTrue(lockOut.finalResult().lockoutSeconds().isEmpty(),
                "the REST contract signals no cool-down duration, so no countdown is invented");
        assertEquals(LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE, lockOut.finalResult().outcome());

        assertEquals(LivenessFinalResult.NextAction.FALLBACK, ServiceLivenessEventMapper.fromFrame(
                frame("reject", null, true, false, false, null), withAction("FALLBACK"),
                "max_retries_exceeded").finalResult().nextAction());
        assertEquals(LivenessFinalResult.NextAction.EXCEPTION, ServiceLivenessEventMapper.fromFrame(
                frame("reject", null, true, false, false, null), withAction("ESCALATE_TO_OPERATOR"),
                "max_retries_exceeded").finalResult().nextAction());
        assertEquals(LivenessFinalResult.NextAction.BLOCK, ServiceLivenessEventMapper.fromFrame(
                frame("reject", null, true, false, false, null), withAction(null),
                "max_retries_exceeded").finalResult().nextAction(),
                "an unknown repeated-failure mode fails closed instead of inviting a retry");
    }

    @Test
    void theDocumentedTerminalActionsMapToTheirOutcomes() {
        assertEquals(LivenessFinalResult.NextAction.LOCKOUT, ServiceLivenessEventMapper.fromFrame(
                frame("locked", null, true, false, false, null), context(), null)
                .finalResult().nextAction());
        assertEquals(LivenessFinalResult.NextAction.EXCEPTION, ServiceLivenessEventMapper.fromFrame(
                frame("escalate_to_operator", null, true, false, false, null), context(), null)
                .finalResult().nextAction(),
                "the action the service applied is authoritative, not the policy it was read from");
        assertEquals(LivenessFinalResult.NextAction.FALLBACK, ServiceLivenessEventMapper.fromFrame(
                frame("failed", null, true, false, false, null), context(), null)
                .finalResult().nextAction());
        for (String action : new String[]{"locked", "escalate_to_operator", "failed"}) {
            assertEquals(LivenessState.TERMINAL_FAILURE, only(ServiceLivenessEventMapper.fromFrame(
                    frame(action, null, true, false, false, null), context(), null)).state(),
                    action + " renders the recovery card, not a retry");
        }
    }

    /**
     * The retry budget is spent on a <em>validation</em>, so that is where the
     * terminal vocabulary actually arrives. Mapping it to "verifying" left the
     * overlay waiting on a session the service had already closed.
     */
    @Test
    void theDocumentedTerminalActionsEndAValidationToo() {
        assertEquals(LivenessFinalResult.NextAction.LOCKOUT, ServiceLivenessEventMapper.fromValidation(
                validation("locked", false, null), context(), null).finalResult().nextAction());
        assertEquals(LivenessFinalResult.NextAction.EXCEPTION, ServiceLivenessEventMapper.fromValidation(
                validation("escalate_to_operator", false, null), context(), null)
                .finalResult().nextAction());
        assertEquals(LivenessFinalResult.NextAction.FALLBACK, ServiceLivenessEventMapper.fromValidation(
                validation("failed", false, null), context(), null).finalResult().nextAction());

        for (String action : new String[]{"locked", "escalate_to_operator", "failed"}) {
            ServiceLivenessEventMapper.Delivery d = ServiceLivenessEventMapper.fromValidation(
                    validation(action, false, null), context(), null);
            assertEquals(LivenessState.TERMINAL_FAILURE, only(d).state(),
                    action + " renders the recovery card on a validation");
            assertTrue(d.ended(), action + " ends the gate");
        }
    }

    // ------------------------------------------------------- challenge verdicts

    @Test
    void aSatisfiedChallengePromptsTheNextOne() {
        ServiceLivenessEventMapper.Delivery d = ServiceLivenessEventMapper.fromValidation(
                validation("retry_challenge", true, challenge("SMILE")), context(), null);
        assertEquals(2, d.states().size(), "detected, then the follow-up prompt");
        assertEquals(LivenessState.CHALLENGE_PASSED, d.states().get(0).state());
        assertEquals("feedback.detected", d.states().get(0).uiMessageKey());
        assertEquals(1, d.states().get(0).challengeIndex(), "the index of the challenge just detected");
        assertEquals(LivenessState.CHALLENGE_PROMPT, d.states().get(1).state());
        assertEquals("SMILE", d.states().get(1).challenge());
        assertEquals(2, d.states().get(1).challengeIndex(), "the follow-up is the next in the sequence");
        assertFalse(d.ended(), "more challenges are required, the gate is still running");
    }

    @Test
    void aSatisfiedChallengeWithoutAFollowUpOnlyReportsDetection() {
        ServiceLivenessEventMapper.Delivery d = ServiceLivenessEventMapper.fromValidation(
                validation("retry_challenge", true, null), context(), null);
        assertEquals(1, d.states().size(),
                "the next frame re-serves the issued challenge and prompts then");
        assertEquals(LivenessState.CHALLENGE_PASSED, d.states().get(0).state());
    }

    @Test
    void aFailedWindowIsARetryableAttemptFailure() {
        LivenessClient.ChallengeResult failed = validation("retry_challenge", false, null);
        ServiceLivenessEventMapper.Delivery d =
                ServiceLivenessEventMapper.fromValidation(failed, context(), null);
        assertEquals(LivenessState.ATTEMPT_FAILED, only(d).state());
        assertEquals("liveness.failed.try_again", only(d).uiMessageKey());
        assertFalse(d.ended(), "the budget still has room");
        assertTrue(ServiceLivenessEventMapper.consumesAttempt(failed),
                "a failed window is an attempt — the adapter counts it before emitting");
    }

    @Test
    void anOpenWindowKeepsVerifying() {
        ServiceLivenessEventMapper.Delivery d = ServiceLivenessEventMapper.fromValidation(
                validation("continue", false, null), withChallengeType("TURN_HEAD_LEFT"), null);
        assertEquals(LivenessState.CHALLENGE_VERIFYING, only(d).state());
        assertEquals("feedback.continue", only(d).uiMessageKey());
        assertEquals("TURN_HEAD_LEFT", only(d).challenge(),
                "no follow-up challenge in this response, so the one in flight is named");
        assertNull(only(d).progress(), "indeterminate: the service reports no score while it waits");
        assertFalse(ServiceLivenessEventMapper.consumesAttempt(validation("continue", false, null)),
                "\"take your time\" must not burn the retry budget");
    }

    @Test
    void aValidationRejectionFollowsTheSameFailureRule() {
        assertEquals(LivenessState.ATTEMPT_FAILED, only(ServiceLivenessEventMapper.fromValidation(
                validation("reject", false, null), context(), null)).state());
        assertEquals(LivenessState.TERMINAL_FAILURE, only(ServiceLivenessEventMapper.fromValidation(
                validation("reject", false, null), context(), "max_retries_exceeded")).state());
        assertEquals("liveness.pad.generic", only(ServiceLivenessEventMapper.fromValidation(
                validation("reject", false, null), context(), "presentation_attack:REPLAY")).uiMessageKey());
    }

    // ------------------------------------------------------- transport and host

    @Test
    void aTransportFailureIsARecoverableDeviceError() {
        ServiceLivenessEventMapper.Delivery d = ServiceLivenessEventMapper.transportFailure(context());
        assertEquals(LivenessState.DEVICE_ERROR, only(d).state());
        assertEquals("liveness.device.error", only(d).uiMessageKey());
        assertEquals(LivenessFailCategory.DEVICE, only(d).failCategory(),
                "a device error never consumes an attempt (design §4)");
        assertFalse(d.ended(), "the session stays open so the next frame can retry");
    }

    @Test
    void aHostCancelAbortsWithoutACard() {
        ServiceLivenessEventMapper.Delivery d = ServiceLivenessEventMapper.aborted(context());
        assertTrue(d.states().isEmpty(), "the final alone already renders the cancelled state");
        assertEquals(LivenessFinalResult.LivenessOutcome.ABORTED, d.finalResult().outcome());
        assertEquals(LivenessFinalResult.NextAction.BLOCK, d.finalResult().nextAction());
        assertEquals(SESSION, d.finalResult().sessionId());
    }

    // ------------------------------------------------------------------ budgets

    @Test
    void theBudgetIsReportedOnceItIsSpent() {
        ServiceLivenessEventMapper.SessionContext spent =
                new ServiceLivenessEventMapper.SessionContext(SESSION, 1, 2, "BLINK", 2, 1, "LOCK_OUT");
        assertEquals(LivenessFailCategory.MAX_RETRIES,
                only(ServiceLivenessEventMapper.fromFrame(
                        frame("reject", null, true, false, false, null), spent, null)).failCategory());
        assertEquals(LivenessFailCategory.GENERIC,
                only(ServiceLivenessEventMapper.fromFrame(
                        frame("reject", null, true, false, false, null), context(), null)).failCategory());
    }

    @Test
    void anUnknownPolicyLeavesTheCounterAndBudgetUnstated() {
        ServiceLivenessEventMapper.SessionContext unknown =
                ServiceLivenessEventMapper.SessionContext.of(SESSION);
        LivenessStateEvent prompt = only(ServiceLivenessEventMapper.fromFrame(
                frame("escalate_to_active", challenge("BLINK"), true, false, false, null), unknown, null));
        assertNull(prompt.challengeIndex(), "no counter without the frozen policy's total");
        assertNull(prompt.challengeTotal());
        assertEquals(0, prompt.attemptsMax(), "0 = the service signalled no budget");
    }

    // ------------------------------------------------------- the contract itself

    @Test
    @DisplayName("the service's English message never reaches the UI, only catalogue keys")
    void theServiceMessageNeverReachesTheUi() {
        String serviceText = "No face detected. Please position your face in the frame.";
        List<LivenessStateEvent> events = List.of(
                ServiceLivenessEventMapper.fromFrame(
                        frame("retry_passive", null, false, false, false, null), context(), null),
                ServiceLivenessEventMapper.fromFrame(
                        frame("escalate_to_active", challenge("BLINK"), true, false, false, null), context(), null),
                ServiceLivenessEventMapper.fromFrame(
                        frame("proceed", null, true, false, false, null), context(), null),
                ServiceLivenessEventMapper.fromFrame(
                        frame("reject", null, true, false, false, null), context(), "max_retries_exceeded"),
                ServiceLivenessEventMapper.transportFailure(context()),
                ServiceLivenessEventMapper.started(context()),
                ServiceLivenessEventMapper.fromValidation(
                        validation("reject", false, null), context(), "presentation_attack:FFT"),
                ServiceLivenessEventMapper.fromValidation(
                        validation("continue", false, null), context(), null),
                ServiceLivenessEventMapper.fromValidation(
                        validation("retry_challenge", true, challenge("SMILE")), context(), null))
                .stream()
                .flatMap(delivery -> delivery.states().stream())
                .toList();
        assertTrue(events.size() >= 10, "every state the mapper can emit is covered, got " + events.size());

        for (LivenessStateEvent event : events) {
            if (event.uiMessageKey() != null) {
                assertTrue(LivenessOverlayPresenter.DEFAULT_MESSAGES.containsKey(event.uiMessageKey()),
                        event.state() + " must carry a catalogue key, got " + event.uiMessageKey());
                assertFalse(event.uiMessageKey().contains(serviceText),
                        "raw service text must never be used as a message key");
            }
            if (event.hint() != null) {
                assertTrue(LivenessOverlayPresenter.DEFAULT_MESSAGES.containsKey(event.hint().uiMessageKey()),
                        "hint keys are catalogue keys too");
            }
        }
        assertFalse(LivenessOverlayPresenter.DEFAULT_MESSAGES.containsValue(serviceText),
                "the service's prose is not a catalogue entry");
    }

    @Test
    void consumesAttemptOnlyCountsFailedWindows() {
        assertFalse(ServiceLivenessEventMapper.consumesAttempt(null));
        assertFalse(ServiceLivenessEventMapper.consumesAttempt(validation(null, false, null)));
        assertFalse(ServiceLivenessEventMapper.consumesAttempt(validation("proceed", true, null)));
        assertTrue(ServiceLivenessEventMapper.consumesAttempt(validation("reject", false, null)));
        assertTrue(ServiceLivenessEventMapper.consumesAttempt(validation("retry_challenge", false, null)));
        // The attempt that spent the last retry was really made, whichever
        // terminal outcome the frozen policy applied to it.
        assertTrue(ServiceLivenessEventMapper.consumesAttempt(validation("locked", false, null)));
        assertTrue(ServiceLivenessEventMapper.consumesAttempt(validation("escalate_to_operator", false, null)));
        assertTrue(ServiceLivenessEventMapper.consumesAttempt(validation("failed", false, null)));
    }

    // ------------------------------------------------------------------ helpers

    private static ServiceLivenessEventMapper.SessionContext context() {
        return new ServiceLivenessEventMapper.SessionContext(SESSION, 1, 2, null, 0, 1, "LOCK_OUT");
    }

    private static ServiceLivenessEventMapper.SessionContext withAction(String onRepeatedFailure) {
        return new ServiceLivenessEventMapper.SessionContext(SESSION, 1, 2, null, 0, 1, onRepeatedFailure);
    }

    private static ServiceLivenessEventMapper.SessionContext withChallengeType(String type) {
        return new ServiceLivenessEventMapper.SessionContext(SESSION, 1, 2, type, 0, 1, "LOCK_OUT");
    }

    private static LivenessStateEvent only(ServiceLivenessEventMapper.Delivery delivery) {
        assertEquals(1, delivery.states().size(),
                "this response renders exactly one state, got " + delivery.states());
        return delivery.states().get(0);
    }

    private static LivenessClient.Challenge challenge(String type) {
        return new LivenessClient.Challenge(CHALLENGE_ID, type, 15_000, 1);
    }

    private static LivenessClient.FrameResult frame(String action, LivenessClient.Challenge challenge,
                                                    boolean faceDetected, boolean multipleFaces,
                                                    boolean padFlag, String padAttackType) {
        return new LivenessClient.FrameResult(UUID.fromString(SESSION), "PASSIVE", faceDetected,
                multipleFaces, 0.9, 0.87, padFlag, padAttackType, action, challenge,
                "No face detected. Please position your face in the frame.");
    }

    private static LivenessClient.ChallengeResult validation(String action, boolean passed,
                                                            LivenessClient.Challenge next) {
        return new LivenessClient.ChallengeResult(UUID.fromString(SESSION), passed, action,
                "Face verification could not be completed. Please try again.", next);
    }
}
