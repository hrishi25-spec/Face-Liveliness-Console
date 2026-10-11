package io.mosip.liveness.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigOverrideTest {

    @Test
    void defaultsApplyWhenNoOverride() {
        LivenessConfig c = LivenessConfig.builder().build();
        EffectivePolicy p = c.effectivePolicy(WorkflowType.RESIDENT_REGISTRATION);
        assertEquals(LivenessConfig.DEFAULT_PASSIVE_THRESHOLD, p.passiveThreshold());
        assertTrue(p.activeLivenessEnabled());
        assertEquals(RepeatedFailureAction.LOCK_OUT, p.onRepeatedFailure());
    }

    @Test
    void supervisorGetsStricterPolicyThanResident() {
        LivenessConfig c = LivenessConfig.builder()
                .workflowOverrides(Map.of(
                        WorkflowType.SUPERVISOR_AUTH, new LivenessPolicy()
                                .withPassiveThreshold(0.92)
                                .withMinChallengeCount(3),
                        WorkflowType.OPERATOR_AUTH, new LivenessPolicy()
                                .withPassiveThreshold(0.85)))
                .build();

        double resident = c.effectivePolicy(WorkflowType.RESIDENT_REGISTRATION).passiveThreshold();
        double operator = c.effectivePolicy(WorkflowType.OPERATOR_AUTH).passiveThreshold();
        EffectivePolicy supervisor = c.effectivePolicy(WorkflowType.SUPERVISOR_AUTH);

        assertEquals(LivenessConfig.DEFAULT_PASSIVE_THRESHOLD, resident);
        assertEquals(0.85, operator, 1e-9);
        assertEquals(0.92, supervisor.passiveThreshold(), 1e-9);
        assertEquals(3, supervisor.minChallengeCount());
        // untouched fields still inherit base
        assertEquals(c.maxRetries(), supervisor.maxRetries());
    }

    @Test
    void workflowCanDisableActiveLiveness() {
        LivenessConfig c = LivenessConfig.builder()
                .workflowOverrides(Map.of(
                        WorkflowType.OPERATOR_AUTH,
                        new LivenessPolicy().withActiveLivenessEnabled(false)))
                .build();

        assertFalse(c.effectivePolicy(WorkflowType.OPERATOR_AUTH).activeLivenessEnabled());
        assertTrue(c.effectivePolicy(WorkflowType.RESIDENT_REGISTRATION).activeLivenessEnabled());
    }

    @Test
    void workflowCanRestrictChallengePool() {
        LivenessConfig c = LivenessConfig.builder()
                .workflowOverrides(Map.of(
                        WorkflowType.SUPERVISOR_AUTH,
                        new LivenessPolicy()
                                .withAllowedChallenges(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE))))
                .build();

        var pool = c.effectivePolicy(WorkflowType.SUPERVISOR_AUTH).allowedChallenges();
        assertEquals(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE), pool);
    }

    @Test
    void invalidThresholdRejected() {
        var b = LivenessConfig.builder().passiveThreshold(1.5);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.mosip.liveness.core.LivenessException.class, b::build);
    }

    @Test
    void activeLivenessRequiresNonEmptyPool() {
        var b = LivenessConfig.builder().supportedChallengeTypes(EnumSet.noneOf(ChallengeType.class));
        org.junit.jupiter.api.Assertions.assertThrows(
                io.mosip.liveness.core.LivenessException.class, b::build);
    }
}
