package io.mosip.liveness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Returned when a session is closed — the audit-friendly final outcome.
 * Maps to the Python framework's SessionSummary schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionSummary {

    private UUID id;
    private WorkflowType workflowType;
    private SessionStatus status;
    private Boolean finalResult;
    private String failureReason;
    private Integer totalFramesEvaluated;
    private Integer totalChallengesIssued;
    private Integer retryCount;
    private Long durationMs;
}
