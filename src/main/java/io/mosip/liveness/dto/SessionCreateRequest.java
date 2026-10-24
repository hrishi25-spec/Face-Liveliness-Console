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

    /**
     * The user type the session runs as.
     *
     * <p>Required, and for a caller that presented a session cookie it must name
     * the account's own type: {@code SessionsController} derives the type from
     * the role and refuses a claim that disagrees with 403. An anonymous caller
     * (the registration client's resident capture) is the one case where this
     * value decides the workflow, because this service holds no resident
     * identity to derive one from.</p>
     */
    @NotNull(message = "workflow_type is required")
    private WorkflowType workflowType;

    @NotBlank(message = "device_id is required")
    private String deviceId;

    private String subjectRef;

    @Builder.Default
    private Boolean online = true;
}
