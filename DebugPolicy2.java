package io.mosip.liveness.characterization;

import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.config.WorkflowPolicyDefaults;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.WorkflowType;

public class DebugPolicy2 {
    public static void main(String[] args) {
        // Test RESIDENT
        System.out.println("=== RESIDENT ===");
        LivenessConfig config = LivenessConfig.builder()
                .passiveThreshold(0.80)
                .build();
        io.mosip.liveness.config.EffectivePolicy ep = config.effectivePolicy(WorkflowType.RESIDENT_REGISTRATION);
        System.out.println("Allowed challenges: " + ep.allowedChallenges());
        System.out.println("Workflow override present: " + (config.workflowOverrides().get(WorkflowType.RESIDENT_REGISTRATION) != null));

        // Check what WorkflowPolicyDefaults returns directly
        io.mosip.liveness.config.EffectivePolicy wpd = WorkflowPolicyDefaults.forWorkflow(WorkflowType.RESIDENT_REGISTRATION);
        System.out.println("WorkflowPolicyDefaults allowed challenges: " + wpd.allowedChallenges());

        // Test OPERATOR
        System.out.println("\n=== OPERATOR ===");
        config = LivenessConfig.builder()
                .passiveThreshold(0.80)
                .build();
        ep = config.effectivePolicy(WorkflowType.OPERATOR_AUTH);
        System.out.println("Allowed challenges: " + ep.allowedChallenges());
        System.out.println("Workflow override present: " + (config.workflowOverrides().get(WorkflowType.OPERATOR_AUTH) != null));

        wpd = WorkflowPolicyDefaults.forWorkflow(WorkflowType.OPERATOR_AUTH);
        System.out.println("WorkflowPolicyDefaults allowed challenges: " + wpd.allowedChallenges());

        // Test SUPERVISOR
        System.out.println("\n=== SUPERVISOR ===");
        config = LivenessConfig.builder()
                .passiveThreshold(0.80)
                .build();
        ep = config.effectivePolicy(WorkflowType.SUPERVISOR_AUTH);
        System.out.println("Allowed challenges: " + ep.allowedChallenges());
        System.out.println("Workflow override present: " + (config.workflowOverrides().get(WorkflowType.SUPERVISOR_AUTH) != null));

        wpd = WorkflowPolicyDefaults.forWorkflow(WorkflowType.SUPERVISOR_AUTH);
        System.out.println("WorkflowPolicyDefaults allowed challenges: " + wpd.allowedChallenges());
    }
}