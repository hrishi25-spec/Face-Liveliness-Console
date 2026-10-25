package io.mosip.liveness.dto;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.android.LivenessRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    @Size(max = 128, message = "nonceHex exceeds the maximum length")
    private String nonceHex;

    private LivenessRole role;
    @Size(max = 128, message = "userId exceeds the maximum length")
    private String userId;
    @Size(max = 64, message = "policyVersion exceeds the maximum length")
    private String policyVersion;
    @Size(max = 128, message = "engineId exceeds the maximum length")
    private String engineId;
    @Size(max = 128, message = "modelVersion exceeds the maximum length")
    private String modelVersion;
    private Boolean engineCertified;
    private Double passiveScore;
    @Size(max = 32, message = "challenges must contain at most 32 entries")
    private List<@NotNull(message = "challenge must not be null") ChallengeType> challenges;
    @Size(max = 32, message = "challengeResults must contain at most 32 entries")
    private List<@NotNull(message = "challengeResult must not be null") Boolean> challengeResults;
    private Long startedEpochMs;
    private Long completedEpochMs;
    @Size(max = 64, message = "bestFrameSha256 exceeds the maximum length")
    private String bestFrameSha256;
    @Size(max = 128, message = "deviceId exceeds the maximum length")
    private String deviceId;
    private Boolean bypassed;
    @Size(max = 4096, message = "signatureHex exceeds the maximum length")
    private String signatureHex;
}
