package io.mosip.liveness.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;

import java.util.Set;

/**
 * Per-workflow policy override. Every field is optional (null = inherit from
 * the base {@link LivenessConfig}). Fluent setters return {@code this}.
 */
public final class LivenessPolicy {

    private Boolean livenessEnabled;
    private Boolean activeLivenessEnabled;
    private Double passiveThreshold;
    private Double minFaceQuality;
    private Integer passiveMinFrames;
    private Integer passiveWindowFrames;
    private Integer minChallengeCount;
    private Integer maxRetries;
    private Long challengeTimeoutMs;
    private Set<ChallengeType> allowedChallenges;
    private RepeatedFailureAction onRepeatedFailure;
    // v3 fields
    private Long maxSessionDurationMs;
    private Integer frameSamplingRate;
    private Integer frameSamplingMinFps;
    private Double combinedPassiveWeight;
    private Double combinedActiveWeight;
    private Double passiveThresholdActive;

    public LivenessPolicy withLivenessEnabled(boolean v) { this.livenessEnabled = v; return this; }
    public LivenessPolicy withActiveLivenessEnabled(boolean v) { this.activeLivenessEnabled = v; return this; }
    public LivenessPolicy withPassiveThreshold(double v) { this.passiveThreshold = v; return this; }
    public LivenessPolicy withMinFaceQuality(double v) { this.minFaceQuality = v; return this; }
    public LivenessPolicy withPassiveMinFrames(int v) { this.passiveMinFrames = v; return this; }
    public LivenessPolicy withPassiveWindowFrames(int v) { this.passiveWindowFrames = v; return this; }
    public LivenessPolicy withMinChallengeCount(int v) { this.minChallengeCount = v; return this; }
    public LivenessPolicy withMaxRetries(int v) { this.maxRetries = v; return this; }
    public LivenessPolicy withChallengeTimeoutMs(long v) { this.challengeTimeoutMs = v; return this; }
    public LivenessPolicy withAllowedChallenges(Set<ChallengeType> v) { this.allowedChallenges = Set.copyOf(v); return this; }
    public LivenessPolicy withOnRepeatedFailure(RepeatedFailureAction v) { this.onRepeatedFailure = v; return this; }
    // v3 setters
    public LivenessPolicy withMaxSessionDurationMs(long v) { this.maxSessionDurationMs = v; return this; }
    public LivenessPolicy withFrameSamplingRate(int v) { this.frameSamplingRate = v; return this; }
    public LivenessPolicy withFrameSamplingMinFps(int v) { this.frameSamplingMinFps = v; return this; }
    public LivenessPolicy withCombinedPassiveWeight(double v) { this.combinedPassiveWeight = v; return this; }
    public LivenessPolicy withCombinedActiveWeight(double v) { this.combinedActiveWeight = v; return this; }
    public LivenessPolicy withPassiveThresholdActive(double v) { this.passiveThresholdActive = v; return this; }

    public Boolean livenessEnabled() { return livenessEnabled; }
    public Boolean activeLivenessEnabled() { return activeLivenessEnabled; }
    public Double passiveThreshold() { return passiveThreshold; }
    public Double minFaceQuality() { return minFaceQuality; }
    public Integer passiveMinFrames() { return passiveMinFrames; }
    public Integer passiveWindowFrames() { return passiveWindowFrames; }
    public Integer minChallengeCount() { return minChallengeCount; }
    public Integer maxRetries() { return maxRetries; }
    public Long challengeTimeoutMs() { return challengeTimeoutMs; }
    public Set<ChallengeType> allowedChallenges() { return allowedChallenges; }
    public RepeatedFailureAction onRepeatedFailure() { return onRepeatedFailure; }
    // v3 getters
    public Long maxSessionDurationMs() { return maxSessionDurationMs; }
    public Integer frameSamplingRate() { return frameSamplingRate; }
    public Integer frameSamplingMinFps() { return frameSamplingMinFps; }
    public Double combinedPassiveWeight() { return combinedPassiveWeight; }
    public Double combinedActiveWeight() { return combinedActiveWeight; }
    public Double passiveThresholdActive() { return passiveThresholdActive; }

    /** @return the first non-null override value, else the base default. */
    static <T> T coalesce(T override, T base) {
        return override != null ? override : base;
    }
}
