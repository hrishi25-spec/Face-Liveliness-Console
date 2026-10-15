package io.mosip.liveness.models.enums;

/** Lifecycle state of a single active-liveness challenge. */
public enum ChallengeStatus {
    /** Issued to the client, awaiting frame submission. */
    ISSUED,
    /** The requested action was observed. */
    PASSED,
    /** The requested action was not observed. */
    FAILED,
    /** The client never responded within the challenge window. */
    TIMEOUT
}
