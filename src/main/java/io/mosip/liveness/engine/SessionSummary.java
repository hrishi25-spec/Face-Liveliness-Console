package io.mosip.liveness.engine;

import io.mosip.liveness.core.ChallengeProgress;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.WorkflowType;

import java.util.List;

/** Audit record returned by {@code closeSession}. */
public record SessionSummary(
        String sessionId,
        WorkflowType workflow,
        Outcome outcome,
        boolean escalatedToActive,
        double finalMedianScore,
        PadAttackType padAttackType,
        int challengesIssued,
        int challengesPassed,
        int challengesFailed,
        long durationMs,
        List<ChallengeProgress> challengeProgressEvents
) {

    /** @deprecated Use the full constructor with challengeProgressEvents. */
    @Deprecated
    public SessionSummary(String sessionId, WorkflowType workflow, Outcome outcome,
                          boolean escalatedToActive, double finalMedianScore,
                          PadAttackType padAttackType, int challengesIssued,
                          int challengesPassed, int challengesFailed, long durationMs) {
        this(sessionId, workflow, outcome, escalatedToActive, finalMedianScore,
                padAttackType, challengesIssued, challengesPassed, challengesFailed,
                durationMs, List.of());
    }

    public enum Outcome {
        PASSED_PASSIVE,
        PASSED_ACTIVE,
        BYPASSED,
        PAD_BLOCKED,
        FAILED_LIVENESS,
        FAILED_MAX_RETRIES_LOCKED_OUT,
        FAILED_MAX_RETRIES_FALLBACK,
        FAILED_MAX_RETRIES_OPERATOR_ESCALATION,
        FAILED_SESSION_TIMEOUT,
        ABORTED
    }

    public boolean passed() {
        return outcome == Outcome.PASSED_PASSIVE || outcome == Outcome.PASSED_ACTIVE
                || outcome == Outcome.BYPASSED;
    }
}
