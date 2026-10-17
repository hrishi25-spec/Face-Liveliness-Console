package io.mosip.liveness.models.enums;

/** Lifecycle state of a liveness session. */
public enum SessionStatus {
    /** Session is open and accepting frames. */
    ACTIVE,
    /** Liveness was verified. */
    PASSED,
    /** Session was rejected (PAD attack, retries exhausted, ...). */
    FAILED,
    /** Session was closed by the client before a verdict was reached. */
    EXPIRED
}
