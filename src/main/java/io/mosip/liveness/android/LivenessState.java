package io.mosip.liveness.android;

/**
 * States of the Android liveness gate (orchestration spec §4).
 *
 * <p>The Android orchestrator is a thin state machine <em>in front of</em> the
 * existing {@link io.mosip.liveness.engine.FaceLivenessEngine} pipeline: it owns
 * the frame source, attempt/retry counting, session nonces and gate validity,
 * while every frame still flows through the shared passive→active engine so
 * Resident/Operator/Supervisor behave identically on Desktop and Android.</p>
 */
public enum LivenessState {
    IDLE,
    /** Frame source + model opening; failure here is a DEVICE_ERROR, not an attempt. */
    INITIALIZING,
    /** Waiting for exactly one acceptable face (quality gate). Hints are emitted, never counted. */
    POSITIONING,
    /** Collecting the passive scoring window; PAD runs on every frame. */
    PASSIVE_EVALUATING,
    /** A system-selected challenge is being displayed. */
    CHALLENGE_PROMPT,
    /** Analysing frames for the requested action; PAD and liveness re-run here. */
    CHALLENGE_VERIFYING,
    /** One challenge satisfied; more may be required by policy. */
    CHALLENGE_PASSED,
    /** Attempt failed; orchestrator decides retry vs terminal. */
    ATTEMPT_FAILED,
    /** Short cool-down before a fresh session (new nonce + new challenge sequence). */
    RETRY_WAIT,
    /** Gate satisfied — hand off to capture/auth inside the validity window. */
    PASSED,
    /** Retry budget exhausted; the policy's onRepeatedFailure mode is applied. */
    TERMINAL_FAILURE,
    /** Device/camera/frame/engine failure. Never counts as an attempt (fail closed). */
    DEVICE_ERROR,
    /** User cancel or lifecycle (app background beyond the grace period). */
    ABORTED
}
