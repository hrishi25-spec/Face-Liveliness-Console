package io.mosip.liveness.engine;

import io.mosip.liveness.core.LivenessErrorCode;

/** Result of validating frames captured during an active challenge. */
public record ValidationResult(
        boolean passed,
        boolean hardFailure,
        String reason,
        int challengesRemaining,   // challenges still required after this pass (-1 if n/a)
        int retriesLeft,           // retries remaining before hard failure (-1 if n/a)
        LivenessErrorCode errorCode,
        String userMessage) {

    public static ValidationResult finalPass() {
        return new ValidationResult(true, false, "challenge_passed", 0, -1, null, null);
    }

    public static ValidationResult partialPass(int remaining) {
        return new ValidationResult(true, false, "challenge_passed", remaining, -1, null, null);
    }

    public static ValidationResult retryAvailable(int retriesLeft) {
        return new ValidationResult(false, false, "challenge_failed", -1, retriesLeft,
                LivenessErrorCode.ACTIVE_CHALLENGE_FAILURE,
                LivenessErrorCode.ACTIVE_CHALLENGE_FAILURE.userMessage());
    }

    public static ValidationResult timedOutRetry(int retriesLeft) {
        return new ValidationResult(false, false, "challenge_timeout", -1, retriesLeft,
                LivenessErrorCode.CHALLENGE_TIMEOUT,
                LivenessErrorCode.CHALLENGE_TIMEOUT.userMessage());
    }

    public static ValidationResult hardFailure(LivenessErrorCode code) {
        return new ValidationResult(false, true, code.name(), -1, 0, code, code.userMessage());
    }

    public static ValidationResult padBlocked() {
        return new ValidationResult(false, true, "pad_detected_during_challenge", -1, 0,
                LivenessErrorCode.PAD_FAILURE, LivenessErrorCode.PAD_FAILURE.userMessage());
    }
}
