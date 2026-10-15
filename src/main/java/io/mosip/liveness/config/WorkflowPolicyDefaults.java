package io.mosip.liveness.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;

import java.util.EnumSet;
import java.util.Set;

/**
 * The single source of truth for each workflow's default operating point.
 *
 * <p>Used by three callers so they can never disagree: the DB seed
 * ({@code V4__session_policy_snapshot.sql}), {@code ConfigService} when a
 * workflow has no {@code config_policies} row, and {@code ConfigController} when
 * it lazily creates one. Everything here is a default — an operator can override
 * any field at runtime through {@code PUT /api/v1/config/{workflowType}}.</p>
 *
 * <p>The workflows differ on purpose. A resident is a member of the public who
 * may be physically assisted by an officer, so a failed challenge escalates to an
 * operator; an operator or supervisor is authenticated staff who must not
 * escalate to themselves, so those workflows fail/retry instead. The remaining
 * differences (threshold, challenge count/pool, timeout, retry budget) follow the
 * same intent: supervisor strictest, resident most forgiving.</p>
 */
public final class WorkflowPolicyDefaults {

    private WorkflowPolicyDefaults() {
    }

    /** Defaults shared by every workflow, overridden per-workflow below. */
    private static final double MIN_FACE_QUALITY = LivenessConfig.DEFAULT_MIN_FACE_QUALITY;
    private static final int PASSIVE_MIN_FRAMES = LivenessConfig.DEFAULT_PASSIVE_MIN_FRAMES;
    private static final int PASSIVE_WINDOW_FRAMES = LivenessConfig.DEFAULT_PASSIVE_WINDOW_FRAMES;
    private static final long MAX_SESSION_DURATION_MS = LivenessConfig.DEFAULT_MAX_SESSION_DURATION_MS;
    private static final int FRAME_SAMPLING_RATE = LivenessConfig.DEFAULT_FRAME_SAMPLING_RATE;
    private static final int FRAME_SAMPLING_MIN_FPS = LivenessConfig.DEFAULT_FRAME_SAMPLING_MIN_FPS;
    private static final double COMBINED_PASSIVE_WEIGHT = LivenessConfig.DEFAULT_COMBINED_PASSIVE_WEIGHT;
    private static final double COMBINED_ACTIVE_WEIGHT = LivenessConfig.DEFAULT_COMBINED_ACTIVE_WEIGHT;

    /**
     * Effective default policy for a workflow. Never null.
     *
     * <p>Notable per-workflow values:</p>
     * <ul>
     *   <li><b>RESIDENT</b> — threshold 0.80, 1 challenge, 20s window, 3 retries,
     *       {@link RepeatedFailureAction#ESCALATE_TO_OPERATOR} (an officer can
     *       assist), challenges blink + smile (easiest to perform).</li>
     *   <li><b>OPERATOR</b> — threshold 0.82, 1 challenge, 15s window, 2 retries,
     *       {@link RepeatedFailureAction#FALLBACK} (client may open a fresh
     *       session), challenges blink + head turns.</li>
     *   <li><b>SUPERVISOR</b> — threshold 0.85, 2 challenges, 15s window, 1 retry,
     *       {@link RepeatedFailureAction#LOCK_OUT} (strictest), all four
     *       challenges.</li>
     * </ul>
     */
    public static EffectivePolicy forWorkflow(WorkflowType workflow) {
        if (workflow == null) {
            workflow = WorkflowType.RESIDENT_REGISTRATION;
        }
        return switch (workflow) {
            case RESIDENT_REGISTRATION -> policy(
                    0.80, 1, 20_000L, 3, RepeatedFailureAction.ESCALATE_TO_OPERATOR,
                    EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE));
            case OPERATOR_AUTH -> policy(
                    0.82, 1, 15_000L, 2, RepeatedFailureAction.FALLBACK,
                    EnumSet.of(ChallengeType.BLINK, ChallengeType.TURN_HEAD_LEFT, ChallengeType.TURN_HEAD_RIGHT));
            case SUPERVISOR_AUTH -> policy(
                    0.85, 2, 15_000L, 1, RepeatedFailureAction.LOCK_OUT,
                    EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE,
                            ChallengeType.TURN_HEAD_LEFT, ChallengeType.TURN_HEAD_RIGHT));
        };
    }

    private static EffectivePolicy policy(double threshold, int minChallengeCount, long timeoutMs,
                                          int maxRetries, RepeatedFailureAction onRepeatedFailure,
                                          Set<ChallengeType> challenges) {
        return new EffectivePolicy(
                true,
                true,
                threshold,
                MIN_FACE_QUALITY,
                PASSIVE_MIN_FRAMES,
                PASSIVE_WINDOW_FRAMES,
                minChallengeCount,
                maxRetries,
                timeoutMs,
                challenges,
                onRepeatedFailure,
                -1.0,   // passiveThresholdActive (sentinel: use passiveThreshold)
                MAX_SESSION_DURATION_MS,
                FRAME_SAMPLING_RATE,
                FRAME_SAMPLING_MIN_FPS,
                COMBINED_PASSIVE_WEIGHT,
                COMBINED_ACTIVE_WEIGHT);
    }
}
