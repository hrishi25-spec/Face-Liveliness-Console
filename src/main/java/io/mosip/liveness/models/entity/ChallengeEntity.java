package io.mosip.liveness.models.entity;

import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.ChallengeType;
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
 * An active-liveness challenge issued to a session, as stored in
 * {@code challenges}.
 *
 * <p>Named {@code ChallengeEntity} to avoid confusion with the engine-layer
 * {@link io.mosip.liveness.core.Challenge} value type.</p>
 */
@Entity
@Table(name = "challenges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChallengeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private LivenessSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "challenge_type", nullable = false, length = 32)
    private ChallengeType challengeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ChallengeStatus status = ChallengeStatus.ISSUED;

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private Integer attemptNumber = 1;

    @Column(name = "timeout_ms", nullable = false)
    private Integer timeoutMs;

    /**
     * True when this challenge was requested by hand
     * ({@code POST /api/v1/sessions/{id}/active-liveness}) rather than by the
     * engine's passive-to-active escalation.
     *
     * <p>A manual challenge runs as an <em>extra</em> check on a session that
     * already passed: its own verdict is persisted on the challenge row and
     * written to the audit trail, but it never rewrites the session's verdict
     * — the passive decision stands either way. Created by
     * {@code V11__challenge_manual_flag.sql}.</p>
     */
    @Column(name = "manual", nullable = false)
    @Builder.Default
    private Boolean manual = false;

    @Column(name = "issued_at")
    private OffsetDateTime issuedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    void onCreate() {
        if (issuedAt == null) {
            issuedAt = OffsetDateTime.now();
        }
    }
}
