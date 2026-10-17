package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.mosip.liveness.android.LivenessFailCategory;
import io.mosip.liveness.android.LivenessFinalResult;
import io.mosip.liveness.android.LivenessHint;
import io.mosip.liveness.android.LivenessState;
import io.mosip.liveness.android.LivenessStateEvent;

/**
 * Every row of the {@code docs/ui-ux-design.md} state→UI table, the §3
 * challenge UX and the §4 failure/retry rules, as headless decisions of
 * {@link LivenessOverlayPresenter}.
 */
class LivenessOverlayPresenterTest {

    // ------------------------------------------------------------ §1 state table

    @Test
    void idleOrMissingEventHidesTheOverlay() {
        assertFalse(LivenessOverlayPresenter.forState(null).visible());
        assertFalse(LivenessOverlayPresenter.forState(event(LivenessState.IDLE)).visible());
        assertNull(LivenessOverlayPresenter.forState(event(LivenessState.IDLE)).card());
        assertFalse(LivenessOverlayPresenter.forFinal(null).visible());
        assertFalse(LivenessOverlayPresenter.forFinal(
                new LivenessFinalResult("s1", null, null, Optional.empty(), 0)).visible());
    }

    @Test
    void initializingDefaultsToLookCamera() {
        LivenessOverlayPresenter.OverlayView v =
                LivenessOverlayPresenter.forState(event(LivenessState.INITIALIZING));
        assertTrue(v.visible());
        assertEquals(LivenessOverlayPresenter.Card.STATUS, v.card());
        assertEquals("liveness.hint.look_camera", v.messageKey(),
                "design §1: INITIALIZING shows 'Look directly at the camera.'");
        assertFalse(v.statusProgressVisible());
    }

    @Test
    void positioningRendersTheActiveHint() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.POSITIONING, null, null, LivenessHint.TOO_FAR, null, null, null));
        assertEquals(LivenessOverlayPresenter.Card.STATUS, v.card());
        assertEquals("liveness.hint.distance", v.messageKey());
    }

    @Test
    void positioningFallsBackToLookCameraWithoutAHint() {
        LivenessOverlayPresenter.OverlayView v =
                LivenessOverlayPresenter.forState(event(LivenessState.POSITIONING));
        assertEquals("liveness.hint.look_camera", v.messageKey());
    }

    @Test
    void passiveEvaluatingShowsCheckingWithIndeterminateProgress() {
        LivenessOverlayPresenter.OverlayView v =
                LivenessOverlayPresenter.forState(event(LivenessState.PASSIVE_EVALUATING));
        assertEquals(LivenessOverlayPresenter.Card.STATUS, v.card());
        assertEquals("liveness.checking", v.messageKey());
        assertTrue(v.statusProgressVisible(), "design §1: indeterminate progress while scoring");
        assertFalse(v.retryVisible(), "scoring is not a failure");
    }

    @Test
    void passedShowsSuccessGreenOvalAndNoRetry() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.PASSED, null, "liveness.success", null, null, null, 1.0));
        assertEquals(LivenessOverlayPresenter.Card.STATUS, v.card());
        assertEquals("liveness.success", v.messageKey());
        assertTrue(v.passedAccent(), "design §1: oval turns green on PASSED");
        assertFalse(v.retryVisible());
        assertTrue(v.cancelVisible());
    }

    @Test
    void abortedShowsCancelledState() {
        LivenessOverlayPresenter.OverlayView v =
                LivenessOverlayPresenter.forState(event(LivenessState.ABORTED));
        assertEquals(LivenessOverlayPresenter.Card.STATUS, v.card());
        assertEquals("liveness.cancelled", v.messageKey());
        assertFalse(v.retryVisible());
    }

    // ------------------------------------------------------------ §3 challenge UX

    @Test
    void challengePromptShowsPromptCardWithIndex() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.CHALLENGE_PROMPT, "BLINK", "prompt.blink", null, 1, 2, null));
        assertEquals(LivenessOverlayPresenter.Card.CHALLENGE, v.card());
        assertEquals("prompt.blink", v.promptKey());
        assertNull(v.feedbackKey(), "no feedback line until frames are analysed");
        assertEquals(1, v.challengeIndex());
        assertEquals(2, v.challengeTotal());
        assertEquals(0.0, v.progress());
        assertFalse(v.progressIndeterminate());
        assertFalse(v.retryVisible(), "a prompt is not a failure");
        assertTrue(v.cancelVisible());
    }

    @Test
    void promptKeyIsDerivedFromTheSystemSelectedChallenge() {
        assertEquals("prompt.smile", LivenessOverlayPresenter.forState(
                event(LivenessState.CHALLENGE_PROMPT, "SMILE", null, null, null, null, null)).promptKey());
        assertEquals("prompt.turn_left", LivenessOverlayPresenter.forState(
                event(LivenessState.CHALLENGE_PROMPT, "TURN_HEAD_LEFT", null, null, null, null, null)).promptKey(),
                "TURN_HEAD_* normalises to the shared prompt key (LivenessStateEvent.challengePromptKey)");
        assertEquals("liveness.checking", LivenessOverlayPresenter.forState(
                event(LivenessState.CHALLENGE_PROMPT, null, null, null, null, null, null)).promptKey(),
                "no challenge issued yet fails closed to the generic status");
    }

    @Test
    void challengeVerifyingKeepsThePromptAndCyclesTheFeedbackLine() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.CHALLENGE_VERIFYING, "BLINK", "feedback.hold", null, 1, 2, 0.6));
        assertEquals(LivenessOverlayPresenter.Card.CHALLENGE, v.card());
        assertEquals("prompt.blink", v.promptKey(), "headline stays the issued challenge (design §3)");
        assertEquals("feedback.hold", v.feedbackKey(), "continue → detected → hold cycle");
        assertEquals(0.6, v.progress());
        assertFalse(v.progressIndeterminate());
    }

    @Test
    void challengeVerifyingIsIndeterminateUntilCombinedScoreIsKnown() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.CHALLENGE_VERIFYING, "SMILE", "feedback.continue", null, 1, 2, null));
        assertTrue(v.progressIndeterminate());
        assertEquals("prompt.smile", v.promptKey());
        assertEquals("feedback.continue", v.feedbackKey());
    }

    @Test
    void challengeVerifyingDefaultsItsFeedbackToContinue() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.CHALLENGE_VERIFYING, "BLINK", null, null, 1, 2, 0.2));
        assertEquals("feedback.continue", v.feedbackKey());
    }

    @Test
    void challengePassedShowsDetectedOnTheStatusCard() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.CHALLENGE_PASSED, "BLINK", "feedback.detected", null, 2, 2, null));
        assertEquals(LivenessOverlayPresenter.Card.STATUS, v.card());
        assertEquals("feedback.detected", v.messageKey());
        assertFalse(v.retryVisible());
    }

    // ------------------------------------------------------------ §4 failure rules

    @Test
    void attemptFailedOffersRetryWithTheTryAgainMessage() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.ATTEMPT_FAILED, null, "liveness.failed.try_again",
                        null, null, null, null));
        assertEquals(LivenessOverlayPresenter.Card.FAILURE, v.card());
        assertEquals("liveness.failed.try_again", v.messageKey());
        assertTrue(v.retryVisible(), "design §4: Retry appears in ATTEMPT_FAILED");
        assertTrue(v.cancelVisible());
    }

    @Test
    void retryWaitKeepsTheRetryAffordance() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.RETRY_WAIT));
        assertEquals(LivenessOverlayPresenter.Card.FAILURE, v.card());
        assertTrue(v.retryVisible(), "design §4: Retry appears in RETRY_WAIT");
    }

    @Test
    void terminalFailureForcesRecoveryGuidanceAndHidesRetry() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.TERMINAL_FAILURE, null, "liveness.pad.generic",
                        null, null, null, null));
        assertEquals(LivenessOverlayPresenter.Card.FAILURE, v.card());
        assertEquals("liveness.max_retries.recovery", v.messageKey(),
                "terminal failure always shows the recovery key (design §4)");
        assertFalse(v.retryVisible(), "max retries reached → no retry button");
        assertTrue(v.cancelVisible());
    }

    @Test
    void deviceErrorIsRecoverableAndOffersRetry() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.DEVICE_ERROR, null, "liveness.device.unavailable",
                        null, null, null, null));
        assertEquals(LivenessOverlayPresenter.Card.FAILURE, v.card());
        assertEquals("liveness.device.unavailable", v.messageKey());
        assertTrue(v.retryVisible(), "design §4: recoverable DEVICE_ERROR keeps Retry");
        assertFalse(v.passedAccent());
    }

    @Test
    void deviceErrorDefaultsToTheDeviceErrorMessage() {
        LivenessOverlayPresenter.OverlayView v =
                LivenessOverlayPresenter.forState(event(LivenessState.DEVICE_ERROR));
        assertEquals("liveness.device.error", v.messageKey());
    }

    @Test
    void padFailureNeverExposesDetectionDetail() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(
                event(LivenessState.ATTEMPT_FAILED, null, "liveness.pad.generic",
                        null, null, null, null));
        String text = LivenessOverlayPresenter.resolve(null, v.messageKey());
        assertEquals(LivenessOverlayPresenter.DEFAULT_MESSAGES.get("liveness.pad.generic"), text);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String banned : new String[] {"pad", "attack", "spoof", "presentation", "photo", "replay"}) {
            assertFalse(lower.contains(banned),
                    "PAD messaging stays generic (design §4): '" + banned + "' leaked into: " + text);
        }
        assertFalse(text.matches(".*\\d.*"), "no numeric detail in the PAD message: " + text);
    }

    // ------------------------------------------------------------ terminal outcomes

    @Test
    void finalPassedShowsSuccessWithAccent() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forFinal(
                new LivenessFinalResult("s1", LivenessFinalResult.LivenessOutcome.PASSED,
                        LivenessFinalResult.NextAction.PROCEED, Optional.empty(), 30));
        assertEquals(LivenessOverlayPresenter.Card.STATUS, v.card());
        assertEquals("liveness.success", v.messageKey());
        assertTrue(v.passedAccent());
        assertFalse(v.retryVisible());
        assertNull(v.lockoutSeconds());
    }

    @Test
    void finalTerminalFailureSurfacesLockoutCountdownOnlyForLockOut() {
        LivenessOverlayPresenter.OverlayView withLockout = LivenessOverlayPresenter.forFinal(
                new LivenessFinalResult("s1", LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE,
                        LivenessFinalResult.NextAction.LOCKOUT, Optional.of(30), 0));
        assertEquals(LivenessOverlayPresenter.Card.FAILURE, withLockout.card());
        assertEquals("liveness.max_retries.recovery", withLockout.messageKey());
        assertEquals(30, withLockout.lockoutSeconds(), "design §4: countdown when lockoutSeconds surface");
        assertFalse(withLockout.retryVisible());

        LivenessOverlayPresenter.OverlayView blocked = LivenessOverlayPresenter.forFinal(
                new LivenessFinalResult("s1", LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE,
                        LivenessFinalResult.NextAction.BLOCK, Optional.empty(), 0));
        assertNull(blocked.lockoutSeconds());
        assertEquals("liveness.max_retries.recovery", blocked.messageKey());
    }

    @Test
    void finalAbortedShowsCancelled() {
        LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forFinal(
                new LivenessFinalResult("s1", LivenessFinalResult.LivenessOutcome.ABORTED,
                        LivenessFinalResult.NextAction.BLOCK, Optional.empty(), 0));
        assertEquals(LivenessOverlayPresenter.Card.STATUS, v.card());
        assertEquals("liveness.cancelled", v.messageKey());
    }

    // ------------------------------------------------------------ cross-cutting rules

    @Test
    void retryAppearsExactlyWhereDesignSection4Allows() {
        Set<LivenessState> retryStates = EnumSet.of(
                LivenessState.ATTEMPT_FAILED, LivenessState.RETRY_WAIT, LivenessState.DEVICE_ERROR);
        for (LivenessState state : LivenessState.values()) {
            if (state == LivenessState.IDLE) {
                continue;
            }
            LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(event(state));
            assertEquals(retryStates.contains(state), v.retryVisible(),
                    "Retry visibility for " + state + " must follow design §4");
            assertTrue(v.cancelVisible(), "Cancel stays available in " + state);
        }
    }

    @Test
    void everyDerivedKeyExistsInTheDefaultCatalogue() {
        for (LivenessState state : LivenessState.values()) {
            LivenessOverlayPresenter.OverlayView v = LivenessOverlayPresenter.forState(event(state));
            for (String key : new String[] {v.messageKey(), v.promptKey(), v.feedbackKey()}) {
                if (key != null) {
                    assertTrue(LivenessOverlayPresenter.DEFAULT_MESSAGES.containsKey(key),
                            "state " + state + " references unknown key '" + key + "'");
                }
            }
        }
        LivenessOverlayPresenter.OverlayView terminal = LivenessOverlayPresenter.forFinal(
                new LivenessFinalResult("s1", LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE,
                        LivenessFinalResult.NextAction.LOCKOUT, Optional.of(10), 0));
        assertTrue(LivenessOverlayPresenter.DEFAULT_MESSAGES.containsKey(terminal.messageKey()));
    }

    // ------------------------------------------------------------ i18n helpers

    @Test
    void resolveFallsBackToTheKeyAndNullToEmpty() {
        assertEquals("Checking face liveness…",
                LivenessOverlayPresenter.resolve(null, "liveness.checking"));
        assertEquals("custom.text",
                LivenessOverlayPresenter.resolve(LivenessOverlayPresenter.DEFAULT_MESSAGES, "custom.text"),
                "unknown keys fall back to themselves (Flutter _msg parity)");
        assertEquals("", LivenessOverlayPresenter.resolve(null, null));
        assertEquals("", LivenessOverlayPresenter.resolve(null, "  "));
    }

    @Test
    void templatesRenderIndexAndLockoutText() {
        assertEquals("Challenge 1 / 2",
                LivenessOverlayPresenter.challengeIndexText(null, 1, 2));
        assertEquals("Try again in 30s",
                LivenessOverlayPresenter.lockoutText(null, 30));
        String index = LivenessOverlayPresenter.DEFAULT_MESSAGES.get("liveness.challenge.index");
        assertTrue(index.contains("{index}") && index.contains("{total}"),
                "index template must be localisable: " + index);
        String lockout = LivenessOverlayPresenter.DEFAULT_MESSAGES.get("liveness.lockout.retry_in");
        assertTrue(lockout.contains("{seconds}"), "lockout template must be localisable: " + lockout);
    }

    @Test
    void defaultCatalogueMatchesTheFlutterSingleSource() throws Exception {
        Path dart = findRepoFile("android_client/lib/liveness/liveness_view.dart");
        assertNotNull(dart, "the Flutter single-source catalogue must ship with the repo");
        String source = Files.readString(dart);
        int start = source.indexOf("defaultMessages = {");
        assertTrue(start >= 0, "LivenessView.defaultMessages not found");
        int end = source.indexOf("};", start);
        assertTrue(end > start, "defaultMessages block not terminated");
        String block = source.substring(start, end);

        Matcher matcher = Pattern.compile("'([A-Za-z0-9_.]+)':\\s*'([^']*)'").matcher(block);
        int shared = 0;
        while (matcher.find()) {
            shared++;
            String key = matcher.group(1);
            String flutterValue = matcher.group(2);
            assertEquals(flutterValue, LivenessOverlayPresenter.DEFAULT_MESSAGES.get(key),
                    "Desktop default for '" + key + "' must equal the Flutter single source");
        }
        assertTrue(shared >= 20, "expected the full Flutter catalogue, parsed " + shared + " keys");

        // Desktop may only add its documented extras — nothing else diverges.
        Set<String> desktopOnly = Set.of(
                "liveness.cancelled", "liveness.device.disconnected",
                "liveness.action.retry", "liveness.action.cancel",
                "liveness.challenge.index", "liveness.lockout.retry_in");
        Set<String> extra = new java.util.HashSet<>(LivenessOverlayPresenter.DEFAULT_MESSAGES.keySet());
        matcher = Pattern.compile("'([A-Za-z0-9_.]+)':\\s*'([^']*)'").matcher(block);
        while (matcher.find()) {
            extra.remove(matcher.group(1));
        }
        assertEquals(desktopOnly, extra,
                "Desktop-only keys must be exactly the documented additions");
    }

    // ------------------------------------------------------------ helpers

    private static LivenessStateEvent event(LivenessState state) {
        return event(state, null, null, null, null, null, null);
    }

    private static LivenessStateEvent event(LivenessState state, String challenge, String key) {
        return event(state, challenge, key, null, null, null, null);
    }

    private static LivenessStateEvent event(LivenessState state, LivenessHint hint, String key,
                                            Integer index, Integer total, Double progress) {
        return event(state, null, key, hint, index, total, progress);
    }

    private static LivenessStateEvent event(LivenessState state, String challenge, String key,
                                            LivenessHint hint, Integer index, Integer total, Double progress) {
        return new LivenessStateEvent("session-1", state, challenge, hint, key,
                1, 3, index, total, progress, LivenessFailCategory.GENERIC);
    }

    private static Path findRepoFile(String relative) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
