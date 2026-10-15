package io.mosip.liveness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosip.liveness.models.enums.FailurePolicy;
import io.mosip.liveness.models.enums.WorkflowType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response for a config policy.
 * Maps to the Python framework's ConfigPolicyResponse schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfigPolicyResponse {

    private UUID id;
    private WorkflowType workflowType;
    private Boolean livenessEnabled;
    private Double passiveThreshold;
    private Boolean activeLivenessEnabled;
    private Integer minChallengeCount;
    private List<String> challengeTypes;
    private Integer challengeTimeoutMs;
    private Integer maxRetryCount;
    private FailurePolicy onRepeatedFailure;
    private OffsetDateTime updatedAt;
}
