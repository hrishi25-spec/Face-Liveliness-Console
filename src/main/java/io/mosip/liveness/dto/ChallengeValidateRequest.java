package io.mosip.liveness.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request to validate challenge frames.
 * Maps to the Python framework's ChallengeValidateRequest schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChallengeValidateRequest {

    // Must be @NotNull, not @NotBlank: @NotBlank only validates CharSequence and
    // Hibernate Validator throws UnexpectedTypeException (HTTP 500) on a UUID.
    @NotNull(message = "challenge_id is required")
    private UUID challengeId;

    @NotEmpty(message = "frames_base64 must contain at least one frame")
    @Size(max = 60, message = "frames_base64 must contain at most 60 frames")
    private List<@NotNull(message = "frame must not be null")
            @Size(max = 8_000_000, message = "frame exceeds the maximum frame size") String> framesBase64;

    @Size(max = 128, message = "nonce exceeds the maximum length")
    private String nonce;
    @Size(max = 60, message = "capturedAtEpochMs must contain at most 60 entries")
    private List<Long> capturedAtEpochMs;
    @Size(max = 60, message = "framesSha256 must contain at most 60 entries")
    private List<@Size(max = 64, message = "frameSha256 exceeds the maximum length") String> framesSha256;
}
