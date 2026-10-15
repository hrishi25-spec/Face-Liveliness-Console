package io.mosip.liveness.core;

/**
 * Real-time UI feedback state during active challenge evaluation.
 * Emitted frame-by-frame by the engine so the UI can show:
 * "Please continue" → "Action detected" → "Hold still" → success.
 */
public enum ChallengeProgress {
    /** Waiting for the user to begin the requested action. UI: "Please continue" */
    AWAITING_ACTION,
    /** The requested facial action has been detected in the current frame. UI: "Action detected" */
    ACTION_DETECTED,
    /** Action detected; user should stabilize for confirmation. UI: "Hold still" */
    HOLD_STILL
}
