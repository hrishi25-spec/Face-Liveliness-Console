package io.mosip.liveness.shared.definitions;

/**
 * Behavior applied when the retry budget for active challenges is exhausted.
 * This is the single source of truth for RepeatedFailureAction used by:
 * - Java service (this file)
 * - FastAPI backend (pad_liveness_backend.app.shared.definitions.repeated_failure_action)
 * - Documentation (docs/configuration.md)
 *
 * <p>All three are terminal and none issues another challenge — extra attempts
 * belong in {@code maxRetryCount}, not in the failure action. They differ only
 * in the outcome signal and whether the client may open a <em>fresh</em>
 * session. The messages are the generic, user-facing strings from the failure
 * table in {@code docs/configuration.md}: they name no detection method and
 * reveal nothing about why the challenge failed.</p>
 */
public enum RepeatedFailureAction {

    /** Hard-fail the session; the operator/device is locked out of further attempts. */
    LOCK_OUT("locked", "max_retries_exceeded:locked_out", false,
            "Face verification failed. Please contact an operator for assistance."),

    /**
     * Hard-fail the session and let the client open a fresh one. The seeded
     * {@code ALLOW_RETRY} mode, and the only one that qualifies for a retry.
     */
    FALLBACK("failed", "max_retries_exceeded", true,
            "We could not verify face liveness. Please try again."),

    /** Hard-fail the session and flag it for supervisor/operator escalation. */
    ESCALATE_TO_OPERATOR("escalate_to_operator", "max_retries_exceeded:escalation_required", false,
            "Face verification could not be completed. An operator will assist you.");

    private final String action;
    private final String failureReason;
    private final boolean mayRetrySession;
    private final String message;

    RepeatedFailureAction(String action, String failureReason, boolean mayRetrySession, String message) {
        this.action = action;
        this.failureReason = failureReason;
        this.mayRetrySession = mayRetrySession;
        this.message = message;
    }

    /** The terminal {@code action} the client reads, e.g. {@code locked}. */
    public String action() {
        return action;
    }

    /**
     * The closed session's {@code failureReason}. Every value keeps the
     * {@code max_retries_exceeded} prefix so a client (and
     * {@code ServiceLivenessEventMapper}) that only knows that one reason still
     * recognises budget exhaustion.
     */
    public String failureReason() {
        return failureReason;
    }

    /** Whether the client may open a new session after this outcome. */
    public boolean mayRetrySession() {
        return mayRetrySession;
    }

    /** The generic, user-facing message for this outcome. */
    public String message() {
        return message;
    }
}