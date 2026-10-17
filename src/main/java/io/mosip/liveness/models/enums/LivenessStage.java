package io.mosip.liveness.models.enums;

/** Which part of the liveness pipeline a frame belongs to. */
public enum LivenessStage {
    /** Passive (single-frame) liveness and PAD scoring. */
    PASSIVE,
    /** Active challenge-response window. */
    ACTIVE,
    /** Session reached a terminal verdict. */
    COMPLETED
}
