package io.mosip.liveness.android;

import java.util.Optional;

/**
 * Terminal outcome delivered to the Dart UI (spec §7
 * {@code LivenessFinalResult}).
 *
 * @param outcome        PASSED | TERMINAL_FAILURE | ABORTED
 * @param nextAction     PROCEED | BLOCK | FALLBACK | LOCKOUT | EXCEPTION (spec §8 terminal())
 * @param lockoutSeconds cool-down when nextAction == LOCKOUT, else empty
 * @param validForSeconds gate validity window when outcome == PASSED
 */
public record LivenessFinalResult(
        String sessionId,
        LivenessOutcome outcome,
        NextAction nextAction,
        Optional<Integer> lockoutSeconds,
        int validForSeconds) {

    public enum LivenessOutcome { PASSED, TERMINAL_FAILURE, ABORTED }

    public enum NextAction { PROCEED, BLOCK, FALLBACK, LOCKOUT, EXCEPTION }
}
