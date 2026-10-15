package io.mosip.liveness.client;

import io.mosip.liveness.android.LivenessFinalResult;
import io.mosip.liveness.android.LivenessState;
import io.mosip.liveness.android.LivenessStateEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure state→view mapping for the Desktop (JavaFX) liveness challenge
 * overlay — the machine behind {@link LivenessChallengeOverlay}.
 *
 * <p>Implements the contracts of {@code docs/ui-ux-design.md}:
 * <ul>
 *   <li>§1 state→UI table — which card each {@link LivenessState} renders;</li>
 *   <li>§3 challenge UX — system-selected prompt, challenge index (1/N),
 *       real-time feedback line ({@code feedback.continue} →
 *       {@code feedback.detected} → {@code feedback.hold}) and normalised
 *       progress;</li>
 *   <li>§4 failure/retry rules — Retry only in {@code ATTEMPT_FAILED} /
 *       {@code RETRY_WAIT} / recoverable {@code DEVICE_ERROR}; terminal
 *       failure shows recovery guidance with no retry; PAD stays
 *       {@code liveness.pad.generic};</li>
 *   <li>§1 "no technical exposure" — only i18n keys and derived numbers leave
 *       this class; no scores, PAD detail or model internals.</li>
 * </ul>
 *
 * <p>No JavaFX types appear here: every decision above is a plain-Java,
 * headless-testable transform from orchestrator events
 * ({@link LivenessStateEvent} / {@link LivenessFinalResult} — the same events
 * Android renders, per design §6 "one state machine drives all surfaces") to
 * an immutable {@link OverlayView}. {@link LivenessChallengeOverlay} only
 * paints the result.
 */
public final class LivenessOverlayPresenter {

    /** Which card the overlay shows. {@code null} when nothing is visible. */
    public enum Card { STATUS, CHALLENGE, FAILURE }

    /**
     * A rendered frame of the overlay: card choice, i18n keys and derived
     * numbers. Text is resolved later through the message lookup so
     * deployments can swap the catalogue (design §6 localisation).
     *
     * @param visible              overlay shown at all ({@code false} = IDLE)
     * @param card                 visible card, or {@code null} when hidden
     * @param messageKey           status/failure message key (STATUS/FAILURE)
     * @param promptKey            challenge headline key (CHALLENGE card)
     * @param feedbackKey          live feedback line key, or {@code null}
     * @param challengeIndex       1-based challenge index, or {@code null}
     * @param challengeTotal       challenges required by policy, or {@code null}
     * @param progress             challenge progress 0..1 (CHALLENGE card)
     * @param progressIndeterminate challenge progress bar indeterminate
     * @param statusProgressVisible indeterminate progress on the STATUS card
     *                             (PASSIVE_EVALUATING warm-up window)
     * @param retryVisible         Retry affordance (design §4 rules)
     * @param cancelVisible        Cancel affordance
     * @param passedAccent         PASSED → oval guide turns green
     * @param lockoutSeconds       LOCK_OUT cool-down, or {@code null}
     */
    public record OverlayView(
            boolean visible,
            Card card,
            String messageKey,
            String promptKey,
            String feedbackKey,
            Integer challengeIndex,
            Integer challengeTotal,
            double progress,
            boolean progressIndeterminate,
            boolean statusProgressVisible,
            boolean retryVisible,
            boolean cancelVisible,
            boolean passedAccent,
            Integer lockoutSeconds) {

        /** Hidden overlay (IDLE / no event): nothing rendered. */
        public static OverlayView hidden() {
            return new OverlayView(false, null, null, null, null, null, null,
                    0.0, false, false, false, false, false, null);
        }
    }

    /**
     * Default English strings (design §9 defaults) — byte-for-byte the
     * Flutter {@code LivenessView.defaultMessages} catalogue so Desktop and
     * Android speak identically, plus the four Desktop-only action/template
     * keys the design's screen sketch and failure table require
     * ({@code liveness.action.*}, {@code liveness.challenge.index},
     * {@code liveness.lockout.retry_in}, {@code liveness.cancelled},
     * {@code liveness.device.disconnected}). Deployments override via i18n.
     */
    public static final Map<String, String> DEFAULT_MESSAGES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // --- shared with android_client/lib/liveness/liveness_view.dart ---
        m.put("liveness.checking", "Checking face liveness…");
        m.put("prompt.blink", "Please blink");
        m.put("prompt.smile", "Please smile");
        m.put("prompt.turn_left", "Please turn your head to the left");
        m.put("prompt.turn_right", "Please turn your head to the right");
        m.put("feedback.continue", "Please continue");
        m.put("feedback.detected", "Action detected");
        m.put("feedback.hold", "Hold still");
        m.put("liveness.success", "Good, face captured successfully");
        m.put("liveness.failed.try_again", "We could not verify face liveness. Please try again.");
        m.put("liveness.pad.generic", "Face verification could not be completed. Please try again.");
        m.put("liveness.max_retries.recovery", "Could not complete verification. Recovery required.");
        m.put("liveness.device.error", "Device error. Please check the camera and try again.");
        m.put("liveness.device.unavailable", "Biometric device not connected. Check the connection.");
        m.put("liveness.camera.unavailable", "Camera not available.");
        m.put("liveness.hint.look_camera", "Look directly at the camera.");
        m.put("liveness.hint.single_person", "Only one person may be in the frame.");
        m.put("liveness.hint.distance", "Move a little closer.");
        m.put("liveness.hint.lighting", "Improve the lighting and hold still.");
        m.put("liveness.hint.look_straight", "Look straight at the camera.");
        m.put("liveness.hint.hold_still", "Hold still.");
        // --- Desktop-only additions (design §1 actions, §4 failure table) ---
        m.put("liveness.cancelled", "Verification cancelled.");
        m.put("liveness.device.disconnected", "Device disconnected. Reconnect and try again.");
        m.put("liveness.action.retry", "Retry");
        m.put("liveness.action.cancel", "Cancel");
        m.put("liveness.challenge.index", "Challenge {index} / {total}");
        m.put("liveness.lockout.retry_in", "Try again in {seconds}s");
        DEFAULT_MESSAGES = Collections.unmodifiableMap(m);
    }

    private LivenessOverlayPresenter() {
    }

    /**
     * Design §1 state→UI table: map an orchestrator state event to the
     * overlay frame. Unknown/null events fail closed to a hidden overlay.
     */
    public static OverlayView forState(LivenessStateEvent event) {
        if (event == null || event.state() == null) {
            return OverlayView.hidden();
        }
        String key = event.uiMessageKey();
        return switch (event.state()) {
            case IDLE -> OverlayView.hidden();

            // "Status card: 'Look directly at the camera.' / hint"
            case INITIALIZING, POSITIONING ->
                    status(first(key, hintKey(event), "liveness.hint.look_camera"), false);

            // "Checking face liveness… + indeterminate progress"
            case PASSIVE_EVALUATING -> status(first(key, "liveness.checking"), true);

            // "Challenge card: 'Please blink' … turns + index (1/N)"
            case CHALLENGE_PROMPT ->
                    challenge(promptKeyFor(event), null, event, 0.0, false);

            // Feedback line cycles continue → detected → hold (design §3);
            // progress reflects combined scoring, indeterminate while unknown.
            case CHALLENGE_VERIFYING -> challenge(
                    promptKeyFor(event),
                    first(key, "feedback.continue"),
                    event,
                    event.progress() != null ? event.progress() : 0.0,
                    event.progress() == null);

            // "'Action detected'; next challenge auto-issued when required"
            case CHALLENGE_PASSED -> status(first(key, "feedback.detected"), false);

            // "Failure card + Retry button (retry available)"
            case ATTEMPT_FAILED, RETRY_WAIT ->
                    failure(first(key, "liveness.failed.try_again"), true);

            // "Recovery guidance per onRepeatedFailure mode; no retry"
            case TERMINAL_FAILURE ->
                    new OverlayView(true, Card.FAILURE, "liveness.max_retries.recovery",
                            null, null, null, null, 0.0, false, false,
                            false, true, false, null);

            // Recoverable device failure: reconnect guidance, retry offered,
            // never consumes an attempt (design §4).
            case DEVICE_ERROR -> failure(first(key, "liveness.device.error"), true);

            // "'Good, face captured successfully'; capture enabled"
            case PASSED ->
                    new OverlayView(true, Card.STATUS, first(key, "liveness.success"),
                            null, null, null, null, 1.0, false, false,
                            false, true, true, null);

            // "Cancelled state; restarting starts a fresh gate"
            case ABORTED -> status(first(key, "liveness.cancelled"), false);
        };
    }

    /**
     * Terminal outcome (design §4): PASSED → success + green oval,
     * TERMINAL_FAILURE → forced recovery key, no retry, LOCK_OUT countdown
     * when the client surfaces {@code lockoutSeconds}, ABORTED → cancelled.
     */
    public static OverlayView forFinal(LivenessFinalResult result) {
        if (result == null || result.outcome() == null) {
            return OverlayView.hidden();
        }
        return switch (result.outcome()) {
            case PASSED ->
                    new OverlayView(true, Card.STATUS, "liveness.success",
                            null, null, null, null, 1.0, false, false,
                            false, true, true, null);
            case ABORTED -> status("liveness.cancelled", false);
            case TERMINAL_FAILURE ->
                    new OverlayView(true, Card.FAILURE, "liveness.max_retries.recovery",
                            null, null, null, null, 0.0, false, false,
                            false, true, false, lockoutOf(result));
        };
    }

    /**
     * Resolve an i18n key through the catalogue; unknown keys fall back to
     * the key itself (Flutter {@code LivenessView._msg} parity), {@code null}
     * / blank resolves to the empty string.
     */
    public static String resolve(Map<String, String> lookup, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        Map<String, String> catalog = lookup == null ? DEFAULT_MESSAGES : lookup;
        return catalog.getOrDefault(key, key);
    }

    /** "Challenge 1 / 2" label from {@code liveness.challenge.index}. */
    public static String challengeIndexText(Map<String, String> lookup, int index, int total) {
        return resolve(lookup, "liveness.challenge.index")
                .replace("{index}", Integer.toString(index))
                .replace("{total}", Integer.toString(total));
    }

    /** "Try again in 30s" lockout countdown text (design §4 LOCK_OUT row). */
    public static String lockoutText(Map<String, String> lookup, int seconds) {
        return resolve(lookup, "liveness.lockout.retry_in")
                .replace("{seconds}", Integer.toString(seconds));
    }

    // ------------------------------------------------------------------ helpers

    private static OverlayView status(String messageKey, boolean statusProgressVisible) {
        return new OverlayView(true, Card.STATUS, messageKey, null, null, null, null,
                0.0, false, statusProgressVisible, false, true, false, null);
    }

    private static OverlayView failure(String messageKey, boolean retryVisible) {
        return new OverlayView(true, Card.FAILURE, messageKey, null, null, null, null,
                0.0, false, false, retryVisible, true, false, null);
    }

    private static OverlayView challenge(String promptKey, String feedbackKey,
                                         LivenessStateEvent event,
                                         double progress, boolean progressIndeterminate) {
        Integer index = event.challengeIndex();
        Integer total = event.challengeTotal();
        boolean numbered = index != null && total != null;
        return new OverlayView(true, Card.CHALLENGE, null, promptKey, feedbackKey,
                numbered ? index : null, numbered ? total : null,
                progress, progressIndeterminate, false, false, true, false, null);
    }

    /** The challenge headline: derived from the issued challenge type, else the event key. */
    private static String promptKeyFor(LivenessStateEvent event) {
        if (event.challenge() != null && !event.challenge().isBlank()) {
            return LivenessStateEvent.challengePromptKey(event.challenge());
        }
        String key = event.uiMessageKey();
        if (key != null && key.startsWith("prompt.")) {
            return key;
        }
        return "liveness.checking";
    }

    private static String hintKey(LivenessStateEvent event) {
        return event.hint() == null ? null : event.hint().uiMessageKey();
    }

    private static Integer lockoutOf(LivenessFinalResult result) {
        if (result.nextAction() != LivenessFinalResult.NextAction.LOCKOUT) {
            return null;
        }
        return result.lockoutSeconds() == null ? null : result.lockoutSeconds().orElse(null);
    }

    private static String first(String... keys) {
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return null;
    }
}
