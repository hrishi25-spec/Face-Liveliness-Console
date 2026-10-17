package io.mosip.liveness.android;

/**
 * Why an attempt failed (orchestration spec §6). PAD detail goes to audit
 * only — the UI receives the generic {@code liveness.pad.generic} message.
 */
public enum LivenessFailReason {
    PAD_DETECTED,
    LOW_LIVENESS,
    CHALLENGE_FAILED,
    CHALLENGE_TIMEOUT,
    DECISION_TIMEOUT
}
