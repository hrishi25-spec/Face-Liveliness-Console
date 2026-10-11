package io.mosip.liveness.android;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.mosip.liveness.audit.AuditEvent;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.config.WorkflowPolicyDefaults;

/**
 * Resolves the effective {@link LivenessGatePolicy} for a role (spec §3).
 *
 * <p>Resolution order mirrors the spec — role-specific override &gt; common
 * default &gt; built-in safe default — but the values are drawn from the same
 * {@link WorkflowPolicyDefaults} table the Desktop client and the REST
 * {@code /config} API use, so Android and Desktop never disagree about a
 * workflow's operating point.</p>
 *
 * <p>Every resolved policy is then passed through
 * {@link LivenessGatePolicy#floored(LivenessGatePolicy)}: the security floor is
 * code, not config, and a synced value cannot weaken it. Disabling liveness
 * entirely requires the {@link #ALLOW_DISABLE_PROPERTY} build flag; without it
 * {@code enabled=false} is upgraded back to {@code true} and the substitution
 * is audited as {@code CONFIG_INVALID} (spec §15: "Disabling liveness via
 * config is impossible without the build flag").</p>
 */
public final class AndroidLivenessPolicyProvider {

    /** System property that must be set at build time to allow liveness=off. */
    public static final String ALLOW_DISABLE_PROPERTY = LivenessGatePolicy.BUILD_FLAG_ALLOW_DISABLE;

    private final AuditLogger audit;
    private final Clock clock;
    private final Map<LivenessRole, Consumer<LivenessGatePolicy.LivenessGatePolicyBuilder>> overrides =
            new ConcurrentHashMap<>();
    private volatile String policyVersion = "android-1.0";

    public AndroidLivenessPolicyProvider(AuditLogger audit, Clock clock) {
        this.audit = audit == null ? AuditLogger.noop() : audit;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Version stamped into every policy and evidence record (spec §3). */
    public void setPolicyVersion(String version) {
        if (version != null && !version.isBlank()) {
            this.policyVersion = version;
        }
    }

    /** Register a role-specific override applied after the shared defaults. */
    public void registerRoleOverride(LivenessRole role,
                                     Consumer<LivenessGatePolicy.LivenessGatePolicyBuilder> customizer) {
        if (role != null && customizer != null) {
            overrides.put(role, customizer);
        }
    }

    public LivenessGatePolicy resolve(LivenessRole role) {
        LivenessRole r = role == null ? LivenessRole.RESIDENT : role;
        LivenessGatePolicy.LivenessGatePolicyBuilder b = new LivenessGatePolicy.LivenessGatePolicyBuilder()
                .version(policyVersion)
                .enabled(true)
                .activeEnabled(true)
                .passiveThreshold(0.80)
                .windowFrames(10)
                .minValidFrames(6)
                .decisionTimeoutSec(6)
                .minChallenges(1)
                .challengeTypes(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE,
                        ChallengeType.TURN_HEAD_LEFT, ChallengeType.TURN_HEAD_RIGHT))
                .challengeTimeoutSec(8)
                .maxRetries(3)
                .onRepeatedFailure(RepeatedFailureAction.ESCALATE_TO_OPERATOR)
                .lockoutSeconds(120)
                .gateValiditySec(30)
                .minFaceQuality(0.50)
                .maxFaces(1);

        // Align the operating point with the shared per-workflow defaults.
        EffectivePolicyDefaults defaults = defaultsFor(r);
        b.passiveThreshold(defaults.threshold)
                .maxRetries(defaults.retries)
                .minChallenges(defaults.minChallenges)
                .challengeTypes(defaults.challenges)
                .onRepeatedFailure(defaults.onRepeatedFailure);

        Consumer<LivenessGatePolicy.LivenessGatePolicyBuilder> customizer = overrides.get(r);
        if (customizer != null) {
            customizer.accept(b);
        }

        LivenessGatePolicy policy = b.build();

        if (!policy.enabled()) {
            if (Boolean.getBoolean(ALLOW_DISABLE_PROPERTY)) {
                audit.log(AuditEvent.of(clock.millis(), "-", toWorkflow(r), AuditEventType.REPEATED_FAILURE_ACTION)
                        .field("event", "LIVENESS_BYPASSED")
                        .field("role", r.name()));
                return policy;
            }
            // Fail closed: upgrade back to enabled and audit the invalid config.
            audit.log(AuditEvent.of(clock.millis(), "-", toWorkflow(r), AuditEventType.CONFIG_CHANGED)
                    .field("event", "LIVENESS_CONFIG_BAD")
                    .field("key", "liveness.enabled")
                    .field("role", r.name())
                    .field("fallbackUsed", "true"));
            policy = withEnabled(policy, true);
        }
        return LivenessGatePolicy.floored(policy);
    }

    private static LivenessGatePolicy withEnabled(LivenessGatePolicy p, boolean enabled) {
        return new LivenessGatePolicy(p.version(), enabled, p.activeEnabled(), p.passiveThreshold(),
                p.windowFrames(), p.minValidFrames(), p.decisionTimeoutSec(), p.minChallenges(),
                p.challengeTypes(), p.challengeTimeoutSec(), p.maxRetries(), p.onRepeatedFailure(),
                p.lockoutSeconds(), p.gateValiditySec(), p.minFaceQuality(), p.maxFaces());
    }

    private record EffectivePolicyDefaults(double threshold, int retries, int minChallenges,
                                           Set<ChallengeType> challenges,
                                           RepeatedFailureAction onRepeatedFailure) { }

    private static EffectivePolicyDefaults defaultsFor(LivenessRole role) {
        WorkflowType wf = toWorkflow(role);
        io.mosip.liveness.config.EffectivePolicy ep = WorkflowPolicyDefaults.forWorkflow(wf);
        Set<ChallengeType> pool = ep.allowedChallenges().isEmpty()
                ? EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE) : ep.allowedChallenges();
        return new EffectivePolicyDefaults(ep.passiveThreshold(), ep.maxRetries(),
                ep.minChallengeCount(), EnumSet.copyOf(pool), ep.onRepeatedFailure());
    }

    /** Map the UI role onto the shared engine workflow table. */
    public static WorkflowType toWorkflow(LivenessRole role) {
        return switch (role == null ? LivenessRole.RESIDENT : role) {
            case RESIDENT -> WorkflowType.RESIDENT_REGISTRATION;
            case OPERATOR -> WorkflowType.OPERATOR_AUTH;
            case SUPERVISOR -> WorkflowType.SUPERVISOR_AUTH;
        };
    }

    /** Normalize a synced challenge-pool CSV; unknown names are dropped and audited upstream. */
    public static Set<ChallengeType> parseChallengeCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(ChallengeType.class);
        }
        EnumSet<ChallengeType> out = EnumSet.noneOf(ChallengeType.class);
        for (String part : csv.split(",")) {
            try {
                out.add(ChallengeType.valueOf(part.trim().toUpperCase(Locale.ROOT)
                        .replace("TURN_LEFT", "TURN_HEAD_LEFT")
                        .replace("TURN_RIGHT", "TURN_HEAD_RIGHT")));
            } catch (IllegalArgumentException ignored) {
                // Unknown type from a newer config: skip; caller audits CONFIG_INVALID.
            }
        }
        return out;
    }

    /** Null-safe role parsing for the Pigeon bridge. */
    public static LivenessRole parseRole(String role) {
        if (role == null) return LivenessRole.RESIDENT;
        try {
            return LivenessRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LivenessRole.RESIDENT;
        }
    }

    /** Defensive equals/hashCode guard so the provider itself stays stateless-safe. */
    @Override
    public boolean equals(Object o) {
        return o instanceof AndroidLivenessPolicyProvider;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
