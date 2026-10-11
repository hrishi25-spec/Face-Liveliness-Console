package io.mosip.liveness.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;

import java.util.Set;

/** Fully-resolved policy for one session (base config + workflow override merged). */
public record EffectivePolicy(
        boolean livenessEnabled,
        boolean activeLivenessEnabled,
        double passiveThreshold,
        double minFaceQuality,
        int passiveMinFrames,
        int passiveWindowFrames,
        int minChallengeCount,
        int maxRetries,
        long challengeTimeoutMs,
        Set<ChallengeType> allowedChallenges,
        RepeatedFailureAction onRepeatedFailure,
        double passiveThresholdActive,
        // v3 fields
        long maxSessionDurationMs,
        int frameSamplingRate,
        int frameSamplingMinFps,
        double combinedPassiveWeight,
        double combinedActiveWeight
) {
    /**
     * Normalizes the {@code passiveThresholdActive} sentinel: any negative value
     * means "fall back to {@code passiveThreshold}". Applied here so the rule
     * holds no matter how the policy was constructed (engine builder or
     * DB-backed {@link io.mosip.liveness.services.ConfigService}).
     */
    public EffectivePolicy {
        if (passiveThresholdActive < 0) {
            passiveThresholdActive = passiveThreshold;
        }
    }

    /**
     * Secondary passive threshold for active challenge window.
     * Configured separately; defaults to passiveThreshold.
     */
    public double passiveThresholdActive() {
        return passiveThresholdActive;
    }

    public String summary() {
        return "threshold=" + passiveThreshold
                + " active=" + activeLivenessEnabled
                + " minChallenges=" + minChallengeCount
                + " timeout=" + challengeTimeoutMs + "ms"
                + " retries=" + maxRetries
                + " onFailure=" + onRepeatedFailure;
    }
}
