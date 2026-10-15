package io.mosip.liveness.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Full liveness/PAD pipeline configuration, including per-workflow overrides
 * for resident registration / operator auth / supervisor auth.
 * Immutable once built via {@link #builder()}.
 */
public final class LivenessConfig {

    public static final double DEFAULT_PASSIVE_THRESHOLD = 0.80;
    public static final double DEFAULT_MIN_FACE_QUALITY = 0.50;
    public static final int DEFAULT_PASSIVE_MIN_FRAMES = 5;
    public static final int DEFAULT_PASSIVE_WINDOW_FRAMES = 7;
    public static final int DEFAULT_MIN_CHALLENGE_COUNT = 2;
    /**
     * Shortest challenge window any policy is allowed to request. Floors
     * {@code challengeTimeoutMs} both at validation and at session start.
     */
    public static final long MIN_CHALLENGE_WINDOW_MS = 15_000L;
    /**
     * Nothing may shrink a challenge window below this: the engine clamps to it,
     * so a shorter value would be silently lengthened and the policy would not
     * mean what it says.
     */
    public static final long ABSOLUTE_MIN_CHALLENGE_WINDOW_MS = 1_000L;

    /**
     * The challenge-window floor actually in force: the configured
     * {@code mosip.liveness.min-challenge-window-ms}, never below the absolute
     * minimum. The engine, the config API and the session-start snapshot all
     * ask this one question, so they cannot disagree about it.
     */
    public static long effectiveMinChallengeWindowMs(long configured) {
        return Math.max(configured, ABSOLUTE_MIN_CHALLENGE_WINDOW_MS);
    }
    /**
     * How long a single active challenge stays open. Shortened to 15s at the
     * product's request — a brisker window keeps the flow snappy. Note this is
     * tighter than the previous 60s (which existed because an impatient window
     * was a false-failure source), so watch the challenge failure rate; the
     * per-workflow {@code config_policies} row can override it at runtime.
     */
    public static final long DEFAULT_CHALLENGE_TIMEOUT_MS = MIN_CHALLENGE_WINDOW_MS;
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final RepeatedFailureAction DEFAULT_REPEATED_FAILURE_ACTION = RepeatedFailureAction.LOCK_OUT;
    // v3 defaults
    public static final long DEFAULT_MAX_SESSION_DURATION_MS = 30_000L;
    /** Default: process every frame. Production configs should set to 2 for CPU efficiency. */
    public static final int DEFAULT_FRAME_SAMPLING_RATE = 1;
    public static final int DEFAULT_FRAME_SAMPLING_MIN_FPS = 10;
    public static final double DEFAULT_COMBINED_PASSIVE_WEIGHT = 0.6;
    public static final double DEFAULT_COMBINED_ACTIVE_WEIGHT = 0.4;
    /** Defaults to same as passiveThreshold; override to disable passive re-eval during active challenges. */
    public static final double DEFAULT_PASSIVE_THRESHOLD_ACTIVE = -1.0; // sentinel: use passiveThreshold

    private final boolean livenessEnabled;
    private final boolean activeLivenessEnabled;
    private final double passiveThreshold;
    private final double minFaceQuality;
    private final int passiveMinFrames;
    private final int passiveWindowFrames;
    private final int minChallengeCount;
    private final long challengeTimeoutMs;
    private final int maxRetries;
    private final Set<ChallengeType> supportedChallengeTypes;
    private final RepeatedFailureAction onRepeatedFailure;
    private final Map<WorkflowType, LivenessPolicy> workflowOverrides;
    // v3 fields
    private final long maxSessionDurationMs;
    private final int frameSamplingRate;
    private final int frameSamplingMinFps;
    private final double combinedPassiveWeight;
    private final double combinedActiveWeight;
    private final double passiveThresholdActive;

    private LivenessConfig(Builder b) {
        this.livenessEnabled = b.livenessEnabled;
        this.activeLivenessEnabled = b.activeLivenessEnabled;
        this.passiveThreshold = b.passiveThreshold;
        this.minFaceQuality = b.minFaceQuality;
        this.passiveMinFrames = b.passiveMinFrames;
        this.passiveWindowFrames = b.passiveWindowFrames;
        this.minChallengeCount = b.minChallengeCount;
        this.challengeTimeoutMs = b.challengeTimeoutMs;
        this.maxRetries = b.maxRetries;
        this.supportedChallengeTypes = Set.copyOf(b.supportedChallengeTypes);
        this.onRepeatedFailure = b.onRepeatedFailure;
        this.workflowOverrides = Map.copyOf(b.workflowOverrides);
        this.maxSessionDurationMs = b.maxSessionDurationMs;
        this.frameSamplingRate = b.frameSamplingRate;
        this.frameSamplingMinFps = b.frameSamplingMinFps;
        this.combinedPassiveWeight = b.combinedPassiveWeight;
        this.combinedActiveWeight = b.combinedActiveWeight;
        this.passiveThresholdActive = b.passiveThresholdActive < 0 ? b.passiveThreshold : b.passiveThresholdActive;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Merge base config with any per-workflow override into an effective policy. */
    public EffectivePolicy effectivePolicy(WorkflowType workflow) {
        LivenessPolicy o = workflowOverrides.get(workflow);
        if (o == null) {
            return new EffectivePolicy(livenessEnabled, activeLivenessEnabled, passiveThreshold,
                    minFaceQuality, passiveMinFrames, passiveWindowFrames, minChallengeCount,
                    maxRetries, challengeTimeoutMs, supportedChallengeTypes, onRepeatedFailure,
                    passiveThresholdActive,
                    maxSessionDurationMs, frameSamplingRate, frameSamplingMinFps,
                    combinedPassiveWeight, combinedActiveWeight);
        }
        Set<ChallengeType> challenges = o.allowedChallenges() != null
                ? o.allowedChallenges() : supportedChallengeTypes;
        return new EffectivePolicy(
                LivenessPolicy.coalesce(o.livenessEnabled(), livenessEnabled),
                LivenessPolicy.coalesce(o.activeLivenessEnabled(), activeLivenessEnabled),
                LivenessPolicy.coalesce(o.passiveThreshold(), passiveThreshold),
                LivenessPolicy.coalesce(o.minFaceQuality(), minFaceQuality),
                LivenessPolicy.coalesce(o.passiveMinFrames(), passiveMinFrames),
                LivenessPolicy.coalesce(o.passiveWindowFrames(), passiveWindowFrames),
                LivenessPolicy.coalesce(o.minChallengeCount(), minChallengeCount),
                LivenessPolicy.coalesce(o.maxRetries(), maxRetries),
                LivenessPolicy.coalesce(o.challengeTimeoutMs(), challengeTimeoutMs),
                challenges,
                LivenessPolicy.coalesce(o.onRepeatedFailure(), onRepeatedFailure),
                LivenessPolicy.coalesce(o.passiveThresholdActive(), passiveThresholdActive),
                LivenessPolicy.coalesce(o.maxSessionDurationMs(), maxSessionDurationMs),
                LivenessPolicy.coalesce(o.frameSamplingRate(), frameSamplingRate),
                LivenessPolicy.coalesce(o.frameSamplingMinFps(), frameSamplingMinFps),
                LivenessPolicy.coalesce(o.combinedPassiveWeight(), combinedPassiveWeight),
                LivenessPolicy.coalesce(o.combinedActiveWeight(), combinedActiveWeight));
    }

    /** Validates all invariants; throws {@link LivenessException} on violation. */
    public void validate() {
        require(passiveThreshold >= 0.0 && passiveThreshold <= 1.0, "passiveThreshold must be within [0,1]");
        require(minFaceQuality >= 0.0 && minFaceQuality <= 1.0, "minFaceQuality must be within [0,1]");
        require(passiveMinFrames >= 1, "passiveMinFrames must be >= 1");
        require(passiveWindowFrames >= passiveMinFrames, "passiveWindowFrames must be >= passiveMinFrames");
        require(minChallengeCount >= 1, "minChallengeCount must be >= 1");
        require(maxRetries >= 0, "maxRetries must be >= 0");
        require(challengeTimeoutMs > 0, "challengeTimeoutMs must be > 0");
        require(!supportedChallengeTypes.isEmpty(), "supportedChallengeTypes must not be empty");
        if (activeLivenessEnabled) {
            require(supportedChallengeTypes.size() >= 1, "active liveness requires at least one challenge type");
        }
        // v3 validations
        require(maxSessionDurationMs > 0, "maxSessionDurationMs must be > 0");
        require(frameSamplingRate >= 1, "frameSamplingRate must be >= 1");
        require(frameSamplingMinFps >= 1, "frameSamplingMinFps must be >= 1");
        require(combinedPassiveWeight >= 0.0 && combinedPassiveWeight <= 1.0,
                "combinedPassiveWeight must be within [0,1]");
        require(combinedActiveWeight >= 0.0 && combinedActiveWeight <= 1.0,
                "combinedActiveWeight must be within [0,1]");
        double wTotal = combinedPassiveWeight + combinedActiveWeight;
        require(Math.abs(wTotal - 1.0) < 0.001,
                "combinedPassiveWeight + combinedActiveWeight must sum to 1.0, got " + wTotal);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new LivenessException(LivenessErrorCode.CONFIGURATION_INVALID, message);
    }

    // ---- getters ----
    public boolean livenessEnabled() { return livenessEnabled; }
    public boolean activeLivenessEnabled() { return activeLivenessEnabled; }
    public double passiveThreshold() { return passiveThreshold; }
    public double minFaceQuality() { return minFaceQuality; }
    public int passiveMinFrames() { return passiveMinFrames; }
    public int passiveWindowFrames() { return passiveWindowFrames; }
    public int minChallengeCount() { return minChallengeCount; }
    public long challengeTimeoutMs() { return challengeTimeoutMs; }
    public int maxRetries() { return maxRetries; }
    public Set<ChallengeType> supportedChallengeTypes() { return supportedChallengeTypes; }
    public RepeatedFailureAction onRepeatedFailure() { return onRepeatedFailure; }
    public Map<WorkflowType, LivenessPolicy> workflowOverrides() { return workflowOverrides; }
    // v3 getters
    public long maxSessionDurationMs() { return maxSessionDurationMs; }
    public int frameSamplingRate() { return frameSamplingRate; }
    public int frameSamplingMinFps() { return frameSamplingMinFps; }
    public double combinedPassiveWeight() { return combinedPassiveWeight; }
    public double combinedActiveWeight() { return combinedActiveWeight; }
    public double passiveThresholdActive() { return passiveThresholdActive; }

    /** Builder with spec defaults. */
    public static final class Builder {
        private boolean livenessEnabled = true;
        private boolean activeLivenessEnabled = true;
        private double passiveThreshold = DEFAULT_PASSIVE_THRESHOLD;
        private double minFaceQuality = DEFAULT_MIN_FACE_QUALITY;
        private int passiveMinFrames = DEFAULT_PASSIVE_MIN_FRAMES;
        private int passiveWindowFrames = DEFAULT_PASSIVE_WINDOW_FRAMES;
        private int minChallengeCount = DEFAULT_MIN_CHALLENGE_COUNT;
        private long challengeTimeoutMs = DEFAULT_CHALLENGE_TIMEOUT_MS;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Set<ChallengeType> supportedChallengeTypes = EnumSet.allOf(ChallengeType.class);
        private RepeatedFailureAction onRepeatedFailure = DEFAULT_REPEATED_FAILURE_ACTION;
        private Map<WorkflowType, LivenessPolicy> workflowOverrides = Map.of();
        // v3 fields
        private long maxSessionDurationMs = DEFAULT_MAX_SESSION_DURATION_MS;
        private int frameSamplingRate = DEFAULT_FRAME_SAMPLING_RATE;
        private int frameSamplingMinFps = DEFAULT_FRAME_SAMPLING_MIN_FPS;
        private double combinedPassiveWeight = DEFAULT_COMBINED_PASSIVE_WEIGHT;
        private double combinedActiveWeight = DEFAULT_COMBINED_ACTIVE_WEIGHT;
        private double passiveThresholdActive = DEFAULT_PASSIVE_THRESHOLD_ACTIVE;

        public Builder livenessEnabled(boolean v) { this.livenessEnabled = v; return this; }
        public Builder activeLivenessEnabled(boolean v) { this.activeLivenessEnabled = v; return this; }
        public Builder passiveThreshold(double v) { this.passiveThreshold = v; return this; }
        public Builder minFaceQuality(double v) { this.minFaceQuality = v; return this; }
        public Builder passiveMinFrames(int v) { this.passiveMinFrames = v; return this; }
        public Builder passiveWindowFrames(int v) { this.passiveWindowFrames = v; return this; }
        public Builder minChallengeCount(int v) { this.minChallengeCount = v; return this; }
        public Builder challengeTimeoutMs(long v) { this.challengeTimeoutMs = v; return this; }
        public Builder maxRetries(int v) { this.maxRetries = v; return this; }
        public Builder supportedChallengeTypes(Set<ChallengeType> v) { this.supportedChallengeTypes = EnumSet.copyOf(v); return this; }
        public Builder onRepeatedFailure(RepeatedFailureAction v) { this.onRepeatedFailure = Objects.requireNonNull(v); return this; }
        public Builder workflowOverrides(Map<WorkflowType, LivenessPolicy> v) { this.workflowOverrides = Map.copyOf(v); return this; }
        // v3 setters
        public Builder maxSessionDurationMs(long v) { this.maxSessionDurationMs = v; return this; }
        public Builder frameSamplingRate(int v) { this.frameSamplingRate = v; return this; }
        public Builder frameSamplingMinFps(int v) { this.frameSamplingMinFps = v; return this; }
        public Builder combinedPassiveWeight(double v) { this.combinedPassiveWeight = v; return this; }
        public Builder combinedActiveWeight(double v) { this.combinedActiveWeight = v; return this; }
        public Builder passiveThresholdActive(double v) { this.passiveThresholdActive = v; return this; }

        public LivenessConfig build() {
            LivenessConfig c = new LivenessConfig(this);
            c.validate();
            return c;
        }
    }
}
