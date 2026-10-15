package io.mosip.liveness.models.entity;

import io.mosip.liveness.models.enums.LivenessStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-frame verdict recorded for a session, as stored in {@code frame_events}.
 * Backs the operational metrics (escalation rate, frame counts) and the audit
 * trail; never returned raw to the client.
 */
@Entity
@Table(name = "frame_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FrameEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private LivenessSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private LivenessStage stage;

    @Column(name = "face_detected", nullable = false)
    @Builder.Default
    private Boolean faceDetected = true;

    @Column(name = "multiple_faces", nullable = false)
    @Builder.Default
    private Boolean multipleFaces = false;

    @Column(name = "face_quality")
    private Double faceQuality;

    @Column(name = "liveness_score")
    private Double livenessScore;

    @Column(name = "pad_flag", nullable = false)
    @Builder.Default
    private Boolean padFlag = false;

    @Column(name = "pad_attack_type", length = 64)
    private String padAttackType;

    @Column(name = "pad_confidence")
    private Double padConfidence;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
