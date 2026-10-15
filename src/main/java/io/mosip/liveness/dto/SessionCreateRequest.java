package io.mosip.liveness.dto;

import io.mosip.liveness.models.enums.WorkflowType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to start a new liveness/PAD verification session.
 * Maps to the Python framework's SessionCreateRequest schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionCreateRequest {

    @NotNull(message = "workflow_type is required")
    private WorkflowType workflowType;

    @NotBlank(message = "device_id is required")
    private String deviceId;

    private String subjectRef;

    @Builder.Default
    private Boolean online = true;
}
