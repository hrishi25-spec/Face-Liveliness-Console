package io.mosip.liveness.models.enums;

/** What to do once the active-challenge retry budget is exhausted. */
public enum FailurePolicy {
    /** Hard-fail the session and lock the subject out of further attempts. */
    LOCK,
    /** Hard-fail the session and flag it for supervisor/operator escalation. */
    ESCALATE,
    /** Hard-fail the session but allow a fresh attempt. */
    ALLOW_RETRY
}
