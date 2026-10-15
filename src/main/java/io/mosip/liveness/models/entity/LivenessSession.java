package io.mosip.liveness.models.entity;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.models.converter.EffectivePolicyJsonConverter;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single liveness/PAD verification session, as stored in
 * {@code liveness_sessions}.
 *
 * <p>Getters/setters are used rather than {@code @Data} so that JPA identity
 * semantics (and lazy associations) are not entangled with equals/hashCode.</p>
 */
@Entity
@Table(name = "liveness_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LivenessSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_type", nullable = false, length = 32)
    private WorkflowType workflowType;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "subject_ref", length = 128)
    private String subjectRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 16)
    @Builder.Default
    private LivenessStage currentStage = LivenessStage.PASSIVE;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "online", nullable = false)
    @Builder.Default
    private Boolean online = true;

    @Column(name = "final_result")
    private Boolean finalResult;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    /**
     * Effective policy resolved once at session creation and frozen for the life
     * of the session, so an admin edit to {@code config_policies} cannot change
     * the operating point of an in-flight session. {@code null} on legacy rows
     * (pre-V4), which the decision path resolves live instead.
     */
    @Convert(converter = EffectivePolicyJsonConverter.class)
    @Column(name = "policy_snapshot", columnDefinition = "text")
    private EffectivePolicy policySnapshot;

    /** When the snapshot above was resolved. */
    @Column(name = "policy_snapshot_at")
    private OffsetDateTime policySnapshotAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
