package io.mosip.liveness.client;

import io.mosip.liveness.android.LivenessFailCategory;
import io.mosip.liveness.android.LivenessFinalResult;
import io.mosip.liveness.android.LivenessHint;
import io.mosip.liveness.android.LivenessState;
import io.mosip.liveness.android.LivenessStateEvent;

import java.util.List;
import java.util.Optional;

/**
 * Translates the service-mediated (REST) liveness protocol into the same
 * {@link LivenessStateEvent} / {@link LivenessFinalResult} pair the in-process
 * {@code AndroidLivenessOrchestrator} emits, so one overlay renders either path
 * (design §6 "one state machine drives all surfaces").
 *
 * <p>The REST path has no local state machine — it has a request/response
 * protocol — so this class is the missing half: a pure function from one
 * response ({@link LivenessClient.FrameResult} /
 * {@link LivenessClient.ChallengeResult}) plus the session's frozen policy and
 * counters ({@link SessionContext}) to the UI updates that response implies.
 * Nothing here talks to a network, a clock or a node, which is why the whole
 * mapping table is unit-testable and why {@link DesktopLivenessAdapter} stays
 * a transport shim.
 *
 * <p>Two rules carry over from the orchestrator path:
 * <ul>
 *   <li><b>Keys, never text.</b> The service's own {@code message} is English
 *       prose for logs and audit — it never reaches the UI. Every event carries
 *       a localisation key and the overlay resolves it (design §1 "no technical
 *       exposure", §9 defaults).</li>
 *   <li><b>Attempts are counted where the budget lives.</b> The service owns the
 *       retry budget; the client only mirrors what it observed, and reports
 *       {@link LivenessFailCategory#MAX_RETRIES} once the observed attempts have
 *       reached the frozen policy's budget.</li>
 * </ul>
 *
 * <h3>What each response maps to</h3>
 * <pre>
 * frame: retry_passive, no face        → POSITIONING (hint: look at the camera)
 * frame: retry_passive, many faces     → POSITIONING (hint: only one person)
 * frame: retry_passive                 → PASSIVE_EVALUATING "Checking face liveness…"
 * frame: escalate_to_active            → CHALLENGE_PROMPT (issued challenge, 1/N)
 * frame/challenge: proceed             → PASSED + final PASSED / PROCEED
 * frame/challenge: reject (PAD)        → ATTEMPT_FAILED, liveness.pad.generic
 * frame/challenge: reject (liveness)   → ATTEMPT_FAILED, liveness.failed.try_again
 * challenge: reject, budget gone      → TERMINAL_FAILURE + final per onRepeatedFailure
 * challenge: locked                    → TERMINAL_FAILURE + final LOCKOUT
 * challenge: escalate_to_operator      → TERMINAL_FAILURE + final EXCEPTION
 * challenge: failed                    → TERMINAL_FAILURE + final FALLBACK
 * challenge: retry_challenge (passed)  → CHALLENGE_PASSED, then the next prompt
 * challenge: retry_challenge (failed)  → ATTEMPT_FAILED (the attempt was consumed)
 * challenge: continue                  → CHALLENGE_VERIFYING (window still open)
 * transport failure                    → DEVICE_ERROR (recoverable, no attempt consumed)
 * session stopped by the host          → final ABORTED / BLOCK
 * </pre>
 *
 * <p><b>Budget-exhausted outcomes have two spellings.</b> The service spends the
 * last retry on a challenge validation and answers with the frozen policy's own
 * action ({@code locked} / {@code escalate_to_operator} / {@code failed}, see
 * {@link io.mosip.liveness.shared.definitions.RepeatedFailureAction}), which the branches above
 * take at face value. A plain {@code reject} whose closed session reports
 * {@code max_retries_exceeded} is the older spelling, kept working because the
 * adapter can read the session's {@code failureReason} — that path derives the
 * same outcome from the policy name instead of the action.
 *
 * <p><b>Known fidelity gaps of the REST contract</b> (the client cannot invent
 * what the service does not send): no cool-down duration for {@code locked}, so
 * the overlay shows the recovery card without the "Try again in Ns" countdown;
 * and no gate-validity window, so a passed session reports {@code validForSeconds}
 * 0.
 */
public final class ServiceLivenessEventMapper {

    // Message keys from the shared catalogue (design §9). Keys only — the
    // service's English `message` is never forwarded to the UI.
    private static final String KEY_CHECKING = "liveness.checking";
    private static final String KEY_LOOK_CAMERA = "liveness.hint.look_camera";
    private static final String KEY_SUCCESS = "liveness.success";
    private static final String KEY_ACTION_DETECTED = "feedback.detected";
    private static final String KEY_CONTINUE = "feedback.continue";
    private static final String KEY_TRY_AGAIN = "liveness.failed.try_again";
    private static final String KEY_PAD_GENERIC = "liveness.pad.generic";
    private static final String KEY_MAX_RETRIES = "liveness.max_retries.recovery";
    private static final String KEY_DEVICE_ERROR = "liveness.device.error";

    /** The service's failure reason once the retry budget is spent. */
    static final String REASON_MAX_RETRIES_EXCEEDED = "max_retries_exceeded";
    /** The service's failure reason prefix for a confirmed presentation attack. */
    static final String REASON_PRESENTATION_ATTACK_PREFIX = "presentation_attack:";

    /** The service's repeated-failure action names (frozen policy). */
    static final String ACTION_LOCK_OUT = "LOCK_OUT";
    static final String ACTION_FALLBACK = "FALLBACK";
    static final String ACTION_ESCALATE_TO_OPERATOR = "ESCALATE_TO_OPERATOR";

    /**
     * The client-side view of the running session: identity, the challenge
     * counter the overlay renders and the attempt budget to report against.
     * Built by {@link DesktopLivenessAdapter} — this class never mutates it.
     *
     * @param sessionId        service session id, {@code null} before one exists
     * @param challengeIndex   1-based index of the challenge in flight, or {@code null}
     * @param challengeTotal   challenges the frozen policy requires, or {@code null}
     * @param challengeType    the challenge being prompted/verified, or {@code null}
     * @param attemptsUsed     attempts consumed so far (mirrors the service's count)
     * @param attemptsMax      the frozen policy's retry budget, 0 when unsignalled
     * @param onRepeatedFailure LOCK_OUT | FALLBACK | ESCALATE_TO_OPERATOR, or {@code null}
     */
    public record SessionContext(
            String sessionId,
            Integer challengeIndex,
            Integer challengeTotal,
            String challengeType,
            int attemptsUsed,
            int attemptsMax,
            String onRepeatedFailure) {

        /** The smallest context the mapper can work with (used when a response arrives late). */
        public static SessionContext of(String sessionId) {
            return new SessionContext(sessionId, null, null, null, 0, 0, null);
        }
    }

    /**
     * The UI updates one service response produces: the state events to render
     * in order, plus the terminal result when the session ended. Both an event
     * and a final are legal in one delivery (the orchestrator does the same for
     * PASSED: the success card, then the outcome).
     */
    public record Delivery(List<LivenessStateEvent> states, LivenessFinalResult finalResult) {

        static Delivery states(LivenessStateEvent... events) {
            return new Delivery(List.of(events), null);
        }

        static Delivery terminal(List<LivenessStateEvent> events, LivenessFinalResult result) {
            return new Delivery(List.copyOf(events), result);
        }

        /** Whether the session ended with this response. */
        public boolean ended() {
            return finalResult != null;
        }
    }

    private ServiceLivenessEventMapper() {
    }

    // ------------------------------------------------------------- entry points

    /**
     * The warm-up a freshly created session renders before the first frame:
     * the same two states the orchestrator emits on {@code start()}.
     */
    public static Delivery started(SessionContext ctx) {
        return Delivery.states(
                event(ctx, LivenessState.INITIALIZING, KEY_CHECKING, null, null, null,
                        LivenessFailCategory.GENERIC),
                event(ctx, LivenessState.POSITIONING, KEY_LOOK_CAMERA, null, null, null,
                        LivenessFailCategory.GENERIC));
    }

    /**
     * @param sessionFailureReason the closed session's {@code failureReason} when the
     *                             adapter could read one (it is how a budget-exhausted
     *                             {@code reject} is told apart from a retryable one),
     *                             otherwise {@code null}
     */
    public static Delivery fromFrame(LivenessClient.FrameResult result, SessionContext ctx,
                                     String sessionFailureReason) {
        String action = result == null ? null : result.action();
        if (action == null) {
            // A response without an action is not a verdict: keep scoring.
            return Delivery.states(scoring(ctx));
        }
        return switch (action) {
            case "proceed" -> passed(ctx);
            case "escalate_to_active" -> Delivery.states(challengePrompt(ctx, challengeTypeOf(result)));
            case "reject" -> rejected(ctx, result.padFlag() || result.padAttackType() != null,
                    sessionFailureReason);
            // The documented budget-exhausted actions: the service has already
            // applied its repeated-failure mode, so the action itself is
            // authoritative (the frozen policy is only the fallback for a service
            // that reports a plain "reject" instead).
            case "locked" -> budgetExhausted(ctx, LivenessFinalResult.NextAction.LOCKOUT);
            case "escalate_to_operator" -> budgetExhausted(ctx, LivenessFinalResult.NextAction.EXCEPTION);
            case "failed" -> budgetExhausted(ctx, LivenessFinalResult.NextAction.FALLBACK);
            case "retry_passive" -> Delivery.states(passiveOrPositioning(ctx, result));
            default -> Delivery.states(scoring(ctx));
        };
    }

    /** @see #fromFrame(LivenessClient.FrameResult, SessionContext, String) */
    public static Delivery fromValidation(LivenessClient.ChallengeResult result, SessionContext ctx,
                                          String sessionFailureReason) {
        String action = result == null ? null : result.action();
        if (action == null) {
            return Delivery.states(verifying(ctx, ctx.challengeType()));
        }
        return switch (action) {
            case "proceed" -> passed(ctx);
            case "reject" -> rejected(ctx, false, sessionFailureReason);
            // The budget is spent on a validation, so this — not a frame response —
            // is where the terminal vocabulary actually arrives. Treating it as
            // "verifying" left the overlay waiting on a session that had ended.
            case "locked" -> budgetExhausted(ctx, LivenessFinalResult.NextAction.LOCKOUT);
            case "escalate_to_operator" -> budgetExhausted(ctx, LivenessFinalResult.NextAction.EXCEPTION);
            case "failed" -> budgetExhausted(ctx, LivenessFinalResult.NextAction.FALLBACK);
            case "retry_challenge" -> result.passed()
                    ? challengeDetected(ctx, result.challenge())
                    : Delivery.states(attemptFailed(ctx, KEY_TRY_AGAIN));
            case "continue" -> Delivery.states(verifying(ctx, challengeTypeOf(result, ctx)));
            default -> Delivery.states(verifying(ctx, challengeTypeOf(result, ctx)));
        };
    }

    /**
     * A transport failure is the service-mediated twin of the orchestrator's
     * {@code DEVICE_ERROR}: recoverable, no attempt consumed, session still open
     * so the next frame or validation retries. The exception's text stays out of
     * the UI — only the catalogue key is emitted.
     */
    public static Delivery transportFailure(SessionContext ctx) {
        return Delivery.states(event(ctx, LivenessState.DEVICE_ERROR, KEY_DEVICE_ERROR, null, null, null,
                LivenessFailCategory.DEVICE));
    }

    /**
     * Whether a validation response consumed an attempt — the client's mirror of
     * the service's retry counter, which the caller applies before building the
     * context so the emitted event reports a spent budget as spent.
     *
     * <p>{@code continue} does not consume one (the service's own window is still
     * open — "take your time"), and neither does a satisfied challenge.
     */
    public static boolean consumesAttempt(LivenessClient.ChallengeResult result) {
        if (result == null || result.action() == null) {
            return false;
        }
        return switch (result.action()) {
            case "retry_challenge" -> !result.passed();
            // Every terminal outcome follows an attempt that was really made: the
            // window elapsed without the action, which is what spent the budget.
            case "reject", "locked", "escalate_to_operator", "failed" -> true;
            default -> false;
        };
    }

    /** The host stopped the session before any verdict: the orchestrator's ABORTED. */
    public static Delivery aborted(SessionContext ctx) {
        return Delivery.terminal(List.of(), new LivenessFinalResult(
                ctx.sessionId(), LivenessFinalResult.LivenessOutcome.ABORTED,
                LivenessFinalResult.NextAction.BLOCK, Optional.empty(), 0));
    }

    // ------------------------------------------------------------- per-action

    private static LivenessStateEvent passiveOrPositioning(SessionContext ctx, LivenessClient.FrameResult r) {
        if (!r.faceDetected()) {
            // The service says only "no face" — the design's generic positioning
            // hint, never the detection detail.
            return event(ctx, LivenessState.POSITIONING, null, LivenessHint.NO_FACE, null, null, category(ctx));
        }
        if (r.multipleFaces()) {
            return event(ctx, LivenessState.POSITIONING, null, LivenessHint.MULTIPLE_FACES, null, null, category(ctx));
        }
        return scoring(ctx);
    }

    private static LivenessStateEvent scoring(SessionContext ctx) {
        return event(ctx, LivenessState.PASSIVE_EVALUATING, KEY_CHECKING, null, null, null, category(ctx));
    }

    private static LivenessStateEvent challengePrompt(SessionContext ctx, String challengeType) {
        return challengePrompt(ctx, challengeType, ctx.challengeIndex());
    }

    /**
     * A prompt for the challenge at {@code index} — the counter is explicit because
     * a follow-up challenge is numbered by the same response that reports the
     * previous one detected.
     */
    private static LivenessStateEvent challengePrompt(SessionContext ctx, String challengeType,
                                                      Integer index) {
        return new LivenessStateEvent(ctx.sessionId(), LivenessState.CHALLENGE_PROMPT,
                challengeType, null, null,
                ctx.attemptsUsed(), ctx.attemptsMax(), index, ctx.challengeTotal(), null, category(ctx));
    }

    private static LivenessStateEvent verifying(SessionContext ctx, String challengeType) {
        // Indeterminate progress (null): the service reports no combined score
        // until it decides, and a fabricated 0% would be invented information.
        return event(ctx, LivenessState.CHALLENGE_VERIFYING, KEY_CONTINUE, null, challengeType, null,
                category(ctx));
    }

    private static LivenessStateEvent attemptFailed(SessionContext ctx, String messageKey) {
        return event(ctx, LivenessState.ATTEMPT_FAILED, messageKey, null, null, null, category(ctx));
    }

    /** One challenge satisfied and the policy requires another. */
    private static Delivery challengeDetected(SessionContext ctx, LivenessClient.Challenge next) {
        LivenessStateEvent detected = event(ctx, LivenessState.CHALLENGE_PASSED, KEY_ACTION_DETECTED,
                null, null, null, category(ctx));
        if (next == null || next.challengeType() == null) {
            // No follow-up challenge in the response: the next frame re-serves the
            // open challenge and prompts then (the service is idempotent there).
            return Delivery.states(detected);
        }
        // The follow-up is the next challenge in the sequence, while the event above
        // still reports the one just detected (orchestrator parity).
        Integer nextIndex = ctx.challengeIndex() == null ? null : ctx.challengeIndex() + 1;
        return Delivery.states(detected, challengePrompt(ctx, next.challengeType(), nextIndex));
    }

    private static Delivery passed(SessionContext ctx) {
        return Delivery.terminal(
                List.of(event(ctx, LivenessState.PASSED, KEY_SUCCESS, null, null, 1.0,
                        LivenessFailCategory.GENERIC)),
                new LivenessFinalResult(ctx.sessionId(), LivenessFinalResult.LivenessOutcome.PASSED,
                        LivenessFinalResult.NextAction.PROCEED, Optional.empty(), 0));
    }

    /**
     * A {@code reject} is the service's generic failure verdict, and the client
     * cannot tell a spent retry budget from a first PAD rejection by {@code action}
     * alone — which is why the adapter reads the closed session's
     * {@code failureReason}. Budget spent → the design's recovery card with no
     * retry; otherwise a retryable attempt failure (PAD stays
     * {@code liveness.pad.generic}, never its detail).
     */
    private static Delivery rejected(SessionContext ctx, boolean presentationAttack,
                                     String sessionFailureReason) {
        if (REASON_MAX_RETRIES_EXCEEDED.equals(sessionFailureReason)) {
            return budgetExhausted(ctx, nextActionFor(ctx.onRepeatedFailure()));
        }
        boolean pad = presentationAttack
                || (sessionFailureReason != null
                        && sessionFailureReason.startsWith(REASON_PRESENTATION_ATTACK_PREFIX));
        return Delivery.states(attemptFailed(ctx, pad ? KEY_PAD_GENERIC : KEY_TRY_AGAIN));
    }

    /**
     * The retry budget is gone: recovery guidance, no retry, and the terminal
     * next-action for the mode that applied — the same outcomes the orchestrator
     * produces for its {@code LOCK_OUT} / {@code FALLBACK} /
     * {@code ESCALATE_TO_OPERATOR}.
     */
    private static Delivery budgetExhausted(SessionContext ctx, LivenessFinalResult.NextAction next) {
        return Delivery.terminal(
                List.of(event(ctx, LivenessState.TERMINAL_FAILURE, KEY_MAX_RETRIES, null, null, null,
                        LivenessFailCategory.MAX_RETRIES)),
                new LivenessFinalResult(ctx.sessionId(),
                        LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE,
                        next,
                        // The REST contract carries no cool-down duration, so the
                        // overlay shows recovery without the countdown.
                        Optional.empty(),
                        0));
    }

    static LivenessFinalResult.NextAction nextActionFor(String onRepeatedFailure) {
        if (onRepeatedFailure == null) {
            // Fail closed: an unknown repeated-failure mode is not a licence to retry.
            return LivenessFinalResult.NextAction.BLOCK;
        }
        return switch (onRepeatedFailure) {
            case ACTION_LOCK_OUT -> LivenessFinalResult.NextAction.LOCKOUT;
            case ACTION_FALLBACK -> LivenessFinalResult.NextAction.FALLBACK;
            case ACTION_ESCALATE_TO_OPERATOR -> LivenessFinalResult.NextAction.EXCEPTION;
            default -> LivenessFinalResult.NextAction.BLOCK;
        };
    }

    // ---------------------------------------------------------------- helpers

    private static LivenessStateEvent event(SessionContext ctx, LivenessState state, String uiMessageKey,
                                           LivenessHint hint, String challenge, Double progress,
                                           LivenessFailCategory category) {
        return new LivenessStateEvent(
                ctx.sessionId(),
                state,
                challenge,
                hint,
                uiMessageKey,
                ctx.attemptsUsed(),
                ctx.attemptsMax(),
                ctx.challengeIndex(),
                ctx.challengeTotal(),
                progress,
                category);
    }

    /** Mirrors the orchestrator: reaching the budget is reported, not hidden. */
    private static LivenessFailCategory category(SessionContext ctx) {
        return ctx.attemptsMax() > 0 && ctx.attemptsUsed() >= ctx.attemptsMax()
                ? LivenessFailCategory.MAX_RETRIES
                : LivenessFailCategory.GENERIC;
    }

    private static String challengeTypeOf(LivenessClient.FrameResult result) {
        return result.challenge() == null ? null : result.challenge().challengeType();
    }

    private static String challengeTypeOf(LivenessClient.ChallengeResult result, SessionContext ctx) {
        return result.challenge() == null || result.challenge().challengeType() == null
                ? ctx.challengeType()
                : result.challenge().challengeType();
    }
}
