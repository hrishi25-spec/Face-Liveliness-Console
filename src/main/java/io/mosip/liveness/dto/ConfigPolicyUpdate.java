package io.mosip.liveness.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to update config policy.
 * Maps to the Python framework's ConfigPolicyUpdate schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigPolicyUpdate {

    private Boolean livenessEnabled;

    // (0,1]: a threshold of exactly 0 would accept every frame.
    @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0")
    private Double passiveThreshold;

    private Boolean activeLivenessEnabled;

    @Min(1) @Max(5)
    private Integer minChallengeCount;

    private List<String> challengeTypes;

    // The absolute floor is the engine's 1s clamp; the operating floor is
    // mosip.liveness.min-challenge-window-ms (default 15s), enforced by the
    // config API and again by EffectivePolicyValidator when a session freezes
    // its snapshot — a shorter window reliably times out honest users.
    @Min(1000) @Max(60000)
    private Integer challengeTimeoutMs;

    @Min(0) @Max(10)
    private Integer maxRetryCount;

    private String onRepeatedFailure;  // LOCK, ESCALATE, ALLOW_RETRY
}
