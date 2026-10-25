package io.mosip.liveness.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to submit a single face frame.
 * Maps to the Python framework's FrameSubmitRequest schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FrameSubmitRequest {

    @NotBlank(message = "frame_base64 is required")
    @Size(max = 8_000_000, message = "frame_base64 exceeds the maximum frame size")
    private String frameBase64;

    @Size(max = 128, message = "nonce exceeds the maximum length")
    private String nonce;
    private Long frameSeq;
    private Long capturedAtEpochMs;
    @Size(max = 64, message = "frameSha256 exceeds the maximum length")
    private String frameSha256;
}
