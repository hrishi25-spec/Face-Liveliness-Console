package io.mosip.liveness.engine;

/** Status returned with each frame assessment. */
public enum AssessmentStatus {
    /** Passive scoring still accumulating frames. */
    SCORING,
    /** Liveness + PAD satisfied — proceed with capture/auth. */
    PASSED,
    /** Passive score below threshold; escalate to Stage 2 challenge-response. */
    ESCALATED_TO_ACTIVE,
    /** A challenge is currently open; frames are being evaluated. */
    CHALLENGE_IN_PROGRESS,
    /** Presentation attack detected — terminal, no retry path. */
    PAD_BLOCKED,
    /** Hard failure (e.g. threshold miss with active liveness disabled). */
    FAILED,
    /** Transient capture problem the user can correct (no face, bad quality...). */
    RETRYABLE_ERROR,
    /** Session duration exceeded maxSessionDurationMs. */
    SESSION_TIMED_OUT
}
