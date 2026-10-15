package io.mosip.liveness.core;

/**
 * Per-frame liveness score during the active challenge window, combining
 * the passive liveness component and the active challenge evaluator component.
 *
 * <p>The engine computes this for every frame during an active challenge.
 * If {@code passiveComponent} drops below the configured {@code passiveThresholdActive},
 * the session fails regardless of whether the active action was performed.</p>
 *
 * @param passiveComponent passive liveness score in [0,1]
 * @param activeComponent  active challenge evaluator score in [0,1] (1.0 = action detected this frame)
 * @param combined         weighted blend per config weights
 */
public record CombinedLivenessScore(
        double passiveComponent,
        double activeComponent,
        double combined
) {
    public CombinedLivenessScore {
        passiveComponent = clamp(passiveComponent);
        activeComponent = clamp(activeComponent);
        combined = clamp(combined);
    }

    /** Score when no data is available yet. */
    public static CombinedLivenessScore empty() {
        return new CombinedLivenessScore(0.0, 0.0, 0.0);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
