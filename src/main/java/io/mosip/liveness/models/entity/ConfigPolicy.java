package io.mosip.liveness.models.entity;

import io.mosip.liveness.models.converter.StringListJsonConverter;
import io.mosip.liveness.models.enums.FailurePolicy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-workflow liveness/PAD policy, as stored in {@code config_policies}
 * (exactly one row per workflow).
 *
 * <p>Editable at runtime through {@code PUT /api/v1/config/{workflowType}};
 * the decision engine re-reads this row on every call, so updates take effect
 * without a redeploy.</p>
 */
@Entity
@Table(name = "config_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_type", nullable = false, unique = true, length = 32)
    private WorkflowType workflowType;

    @Column(name = "liveness_enabled", nullable = false)
    @Builder.Default
    private Boolean livenessEnabled = true;

    @Column(name = "passive_threshold", nullable = false)
    @Builder.Default
    private Double passiveThreshold = io.mosip.liveness.config.LivenessConfig.DEFAULT_PASSIVE_THRESHOLD;

    @Column(name = "active_liveness_enabled", nullable = false)
    @Builder.Default
    private Boolean activeLivenessEnabled = true;

    @Column(name = "min_challenge_count", nullable = false)
    @Builder.Default
    private Integer minChallengeCount = 1;

    /** Lower-case challenge names, e.g. {@code ["blink", "smile", "turn_left"]}. */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "challenge_types", nullable = false, columnDefinition = "text")
    @Builder.Default
    private List<String> challengeTypes =
            new ArrayList<>(List.of("blink", "smile", "turn_left", "turn_right"));

    /** Challenge window; a full minute so a person has time to perform the action. */
    @Column(name = "challenge_timeout_ms", nullable = false)
    @Builder.Default
    private Integer challengeTimeoutMs = 15_000;

    @Column(name = "max_retry_count", nullable = false)
    @Builder.Default
    private Integer maxRetryCount = 3;

    @Enumerated(EnumType.STRING)
    @Column(name = "on_repeated_failure", nullable = false, length = 16)
    @Builder.Default
    private FailurePolicy onRepeatedFailure = FailurePolicy.LOCK;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
