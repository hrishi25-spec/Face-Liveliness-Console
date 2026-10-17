package io.mosip.liveness.dto;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.android.LivenessRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request payload for server-side verification of client liveness evidence.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvidenceVerificationRequest {

    @NotNull(message = "sessionId is required")
    private String sessionId;

    @NotBlank(message = "nonceHex is required")
    private String nonceHex;

    private LivenessRole role;
    private String userId;
    private String policyVersion;
    private String engineId;
    private String modelVersion;
    private Boolean engineCertified;
    private Double passiveScore;
    private List<ChallengeType> challenges;
    private List<Boolean> challengeResults;
    private Long startedEpochMs;
    private Long completedEpochMs;
    private String bestFrameSha256;
    private String deviceId;
    private Boolean bypassed;
    private String signatureHex;
}
