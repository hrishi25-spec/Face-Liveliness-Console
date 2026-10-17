package io.mosip.liveness.android;

import java.util.Map;

/**
 * Events delivered to the Dart UI through the Pigeon {@code LivenessFlutterApi}
 * (spec §7 {@code LivenessStateEvent}). Only generic state and i18n keys cross
 * the bridge — never raw scores, PAD flags or pixels (R2, R5).
 *
 * @param state           {@link LivenessState} name
 * @param challenge       current {@link io.mosip.liveness.core.ChallengeType} name, if prompting
 * @param hint            {@link LivenessHint} name, if a positioning hint is active
 * @param uiMessageKey    localisation key, not raw text
 * @param challengeIndex  1-based index of the current challenge
 * @param challengeTotal  total challenges required by policy
 * @param progress        0..1 UI ring value (derived, not the raw score)
 */
public record LivenessStateEvent(
        String sessionId,
        LivenessState state,
        String challenge,
        LivenessHint hint,
        String uiMessageKey,
        int attemptsUsed,
        int attemptsMax,
        Integer challengeIndex,
        Integer challengeTotal,
        Double progress,
        LivenessFailCategory failCategory) {

    /** Localisation keys shipped by default (spec §9 defaults, i18n overridable). */
    public static final Map<LivenessState, String> DEFAULT_UI_KEYS = Map.of(
            LivenessState.PASSIVE_EVALUATING, "liveness.checking",
            LivenessState.CHALLENGE_PROMPT, "liveness.checking",
            LivenessState.PASSED, "liveness.success",
            LivenessState.TERMINAL_FAILURE, "liveness.max_retries.recovery");

    /** Keys for the per-challenge prompts (prompt.blink / prompt.smile / ...). */
    public static String challengePromptKey(String challengeType) {
        if (challengeType == null) return "liveness.checking";
        String normalized = challengeType
                .replace("TURN_HEAD_LEFT", "TURN_LEFT")
                .replace("TURN_HEAD_RIGHT", "TURN_RIGHT");
        return "prompt." + normalized.toLowerCase(java.util.Locale.ROOT);
    }
}
