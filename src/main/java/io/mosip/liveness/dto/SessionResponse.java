package io.mosip.liveness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response for a liveness session.
 * Maps to the Python framework's SessionResponse schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionResponse {

    private UUID id;
    private WorkflowType workflowType;

    /**
     * The policy frozen for this session at creation (null on legacy rows).
     * At least {@code passiveThreshold}, the challenge pool/count, the window and
     * the repeated-failure action differ by {@code workflowType}.
     */
    private EffectivePolicy policy;
    private String deviceId;
    private SessionStatus status;
    private LivenessStage currentStage;
    private Integer retryCount;
    private Boolean online;
    private Boolean finalResult;
    private String failureReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime closedAt;
}
