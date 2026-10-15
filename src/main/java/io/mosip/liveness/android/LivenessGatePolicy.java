package io.mosip.liveness.android;

import java.util.EnumSet;
import java.util.Set;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;

/**
 * Immutable per-session liveness policy after per-role resolution
 * (spec §3). Android resolves via {@link AndroidLivenessPolicyProvider} on top
 * of the shared {@link io.mosip.liveness.config.WorkflowPolicyDefaults} table,
 * then clamps every value to the hard security floor (spec §3: code constants
 * that cannot be weakened by any synced config).
 *
 * @param version recorded in every evidence record
 */
public record LivenessGatePolicy(
        String version,
        boolean enabled,
        boolean activeEnabled,
        double passiveThreshold,
        int windowFrames,
        int minValidFrames,
        int decisionTimeoutSec,
        int minChallenges,
        Set<ChallengeType> challengeTypes,
        int challengeTimeoutSec,
        int maxRetries,
        RepeatedFailureAction onRepeatedFailure,
        int lockoutSeconds,
        int gateValiditySec,
        double minFaceQuality,
        int maxFaces) {

    // ---- Security floor: code constants; config may only strengthen (spec §3). ----
    public static final double FLOOR_PASSIVE_THRESHOLD = 0.60;
    public static final int CEILING_MAX_RETRIES = 5;
    public static final int MIN_CHALLENGE_TIMEOUT_SEC = 3;
    public static final int MAX_CHALLENGE_TIMEOUT_SEC = 20;
    /** Liveness cannot be switched off unless the build allows it (spec §15). */
    public static final String BUILD_FLAG_ALLOW_DISABLE = "mosip.liveness.android.allow-disable";

    /** Safe default if a policy value is missing/invalid: use + audit CONFIG_INVALID (R1). */
    public static final String SAFE_DEFAULT_VERSION = "android-safe-default";

    public LivenessGatePolicy {
        if (version == null || version.isBlank()) {
            version = SAFE_DEFAULT_VERSION;
        }
        challengeTypes = challengeTypes == null || challengeTypes.isEmpty()
                ? EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE,
                             ChallengeType.TURN_HEAD_LEFT, ChallengeType.TURN_HEAD_RIGHT)
                : EnumSet.copyOf(challengeTypes);
        if (minChallenges < 1) minChallenges = 1;
        if (minChallenges > challengeTypes.size()) minChallenges = challengeTypes.size();
    }

    /** Apply the floor: never below threshold-floor, never above retry ceiling, window in [3,20]s. */
    public static LivenessGatePolicy floored(LivenessGatePolicy p) {
        double threshold = Math.max(FLOOR_PASSIVE_THRESHOLD, p.passiveThreshold);
        int retries = Math.min(CEILING_MAX_RETRIES, Math.max(0, p.maxRetries));
        int timeout = Math.min(MAX_CHALLENGE_TIMEOUT_SEC,
                Math.max(MIN_CHALLENGE_TIMEOUT_SEC, p.challengeTimeoutSec));
        return new LivenessGatePolicy(p.version(), p.enabled(), p.activeEnabled(), threshold,
                Math.max(1, p.windowFrames()), Math.max(1, p.minValidFrames()),
                Math.max(1, p.decisionTimeoutSec()), p.minChallenges(), p.challengeTypes(),
                timeout, retries, p.onRepeatedFailure(), Math.max(0, p.lockoutSeconds()),
                Math.max(1, p.gateValiditySec()), p.minFaceQuality(), Math.max(1, p.maxFaces()));
    }

    /** Engine-side equivalent policy for the shared pipeline (framed from this gate policy). */
    public io.mosip.liveness.config.EffectivePolicy toEffectivePolicy() {
        return new io.mosip.liveness.config.EffectivePolicy(
                enabled, activeEnabled, passiveThreshold, minFaceQuality, minValidFrames,
                windowFrames, minChallenges, maxRetries, challengeTimeoutSec * 1000L,
                challengeTypes, onRepeatedFailure, -1.0,
                (long) (decisionTimeoutSec + challengeTimeoutSec) * 1000L,
                1, 10, 0.6, 0.4);
    }

    /**
     * A string form suitable for the audit trail and evidence records.
     * Never includes secrets; every number is policy, not PII.
     */
    public String auditSummary() {
        return "v=" + version + " thr=" + passiveThreshold
                + " active=" + activeEnabled + " minCh=" + minChallenges
                + " win=" + windowFrames + " retries=" + maxRetries
                + " onFailure=" + onRepeatedFailure;
    }

    /** Epoch-second gate expiry for a session started at {@code startEpochSec}. */
    public long validUntilEpochSec(long startEpochSec) {
        return startEpochSec + gateValiditySec;
    }

    /** Fluent builder used by the policy provider; every field has a safe default. */
    public static final class LivenessGatePolicyBuilder {
        private String version = SAFE_DEFAULT_VERSION;
        private boolean enabled = true;
        private boolean activeEnabled = true;
        private double passiveThreshold = 0.80;
        private int windowFrames = 10;
        private int minValidFrames = 6;
        private int decisionTimeoutSec = 6;
        private int minChallenges = 1;
        private Set<ChallengeType> challengeTypes = EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE,
                ChallengeType.TURN_HEAD_LEFT, ChallengeType.TURN_HEAD_RIGHT);
        private int challengeTimeoutSec = 8;
        private int maxRetries = 3;
        private RepeatedFailureAction onRepeatedFailure = RepeatedFailureAction.ESCALATE_TO_OPERATOR;
        private int lockoutSeconds = 120;
        private int gateValiditySec = 30;
        private double minFaceQuality = 0.50;
        private int maxFaces = 1;

        public LivenessGatePolicyBuilder version(String v) { this.version = v; return this; }
        public LivenessGatePolicyBuilder enabled(boolean v) { this.enabled = v; return this; }
        public LivenessGatePolicyBuilder activeEnabled(boolean v) { this.activeEnabled = v; return this; }
        public LivenessGatePolicyBuilder passiveThreshold(double v) { this.passiveThreshold = v; return this; }
        public LivenessGatePolicyBuilder windowFrames(int v) { this.windowFrames = v; return this; }
        public LivenessGatePolicyBuilder minValidFrames(int v) { this.minValidFrames = v; return this; }
        public LivenessGatePolicyBuilder decisionTimeoutSec(int v) { this.decisionTimeoutSec = v; return this; }
        public LivenessGatePolicyBuilder minChallenges(int v) { this.minChallenges = v; return this; }
        public LivenessGatePolicyBuilder challengeTypes(Set<ChallengeType> v) {
            this.challengeTypes = v == null ? this.challengeTypes : EnumSet.copyOf(v); return this;
        }
        public LivenessGatePolicyBuilder challengeTimeoutSec(int v) { this.challengeTimeoutSec = v; return this; }
        public LivenessGatePolicyBuilder maxRetries(int v) { this.maxRetries = v; return this; }
        public LivenessGatePolicyBuilder onRepeatedFailure(RepeatedFailureAction v) { this.onRepeatedFailure = v; return this; }
        public LivenessGatePolicyBuilder lockoutSeconds(int v) { this.lockoutSeconds = v; return this; }
        public LivenessGatePolicyBuilder gateValiditySec(int v) { this.gateValiditySec = v; return this; }
        public LivenessGatePolicyBuilder minFaceQuality(double v) { this.minFaceQuality = v; return this; }
        public LivenessGatePolicyBuilder maxFaces(int v) { this.maxFaces = v; return this; }

        public LivenessGatePolicy build() {
            return new LivenessGatePolicy(version, enabled, activeEnabled, passiveThreshold,
                    windowFrames, minValidFrames, decisionTimeoutSec, minChallenges, challengeTypes,
                    challengeTimeoutSec, maxRetries, onRepeatedFailure, lockoutSeconds,
                    gateValiditySec, minFaceQuality, maxFaces);
        }
    }
}
