package io.mosip.liveness.core;

/**
 * Result of evaluating a single frame during the active challenge window.
 * Contains the active-challenge-specific result AND the re-evaluated passive
 * liveness score for that frame. The orchestrator uses both to determine
 * whether the session should continue, pass, or fail.
 *
 * @param actionDetected       did this frame show the requested action?
 * @param progress             UI feedback state for this frame
 * @param passiveLivenessScore re-evaluated passive liveness score for this frame
 * @param combinedScore        weighted blend of passive + active components
 * @param padVerdict           PAD check result for this frame
 */
public record ActiveFrameResult(
        boolean actionDetected,
        ChallengeProgress progress,
        double passiveLivenessScore,
        double combinedScore,
        PadVerdict padVerdict
) {
    /** PAD attack detected on this frame. */
    public boolean padBlocked() {
        return padVerdict != null && padVerdict.attackDetected();
    }

    /** Passive score dropped below the active-phase threshold. */
    public boolean passiveFailed(double thresholdActive) {
        return passiveLivenessScore < thresholdActive;
    }
}
