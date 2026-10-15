package io.mosip.liveness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * One session row of the operator feeds ({@code GET /api/v1/sessions} and the
 * failed half of {@code GET /api/v1/rejections}).
 *
 * <p>Deliberately <em>not</em> {@link SessionResponse}: that shape carries the
 * session's frozen policy and is answered to the session's owner, while these
 * feeds are service-wide lists. The fields here are the ones an operator needs
 * to triage a run — who, which workflow, how far it got, why it stopped — and
 * nothing that widens the surface: no {@code subjectRef} (a resident reference
 * is the owner's, not a list's), no policy snapshot, no frame data. A test
 * asserts the absence rather than trusting the field list to stay short.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionListItem {

    private UUID id;
    private WorkflowType workflowType;
    private String deviceId;
    private SessionStatus status;
    private LivenessStage currentStage;
    private Integer retryCount;
    private Boolean finalResult;
    /** Why the session stopped, e.g. {@code presentation_attack:print}; null while it has no verdict. */
    private String failureReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime closedAt;
}
