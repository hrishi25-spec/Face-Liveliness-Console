package io.mosip.liveness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of server-side liveness evidence verification.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvidenceVerificationResponse {

    private boolean verified;
    private String sessionId;
    private String failureReason;
    private List<String> warnings;
}
