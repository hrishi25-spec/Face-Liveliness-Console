package io.mosip.liveness.characterization;

import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.WorkflowType;

public class DebugPolicy {
    public static void main(String[] args) {
        // Test RESIDENT
        System.out.println("=== RESIDENT ===");
        LivenessConfig config = LivenessConfig.builder()
                .passiveThreshold(0.80)
                .build();
        System.out.println("Allowed challenges: " + config.effectivePolicy(WorkflowType.RESIDENT_REGISTRATION).allowedChallenges());

        // Test OPERATOR
        System.out.println("\n=== OPERATOR ===");
        config = LivenessConfig.builder()
                .passiveThreshold(0.80)
                .build();
        System.out.println("Allowed challenges: " + config.effectivePolicy(WorkflowType.OPERATOR_AUTH).allowedChallenges());

        // Test SUPERVISOR
        System.out.println("\n=== SUPERVISOR ===");
        config = LivenessConfig.builder()
                .passiveThreshold(0.80)
                .build();
        System.out.println("Allowed challenges: " + config.effectivePolicy(WorkflowType.SUPERVISOR_AUTH).allowedChallenges());
    }
}