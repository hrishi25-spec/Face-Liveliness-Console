package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.FrameEvent;
import io.mosip.liveness.models.enums.LivenessStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FrameEventRepository extends JpaRepository<FrameEvent, UUID> {

    long countBySessionId(UUID sessionId);

    @Query("SELECT COUNT(DISTINCT f.session.id) FROM FrameEvent f WHERE f.stage = :stage")
    long countDistinctSessionsByStage(@Param("stage") LivenessStage stage);

    /**
     * Chronological passive-stage liveness scores for one session — the input
     * window for the median-voting decision. Ordered by creation time (with the
     * id as tiebreaker, since several events can share a timestamp within one
     * transaction) so the caller always sees the frames in capture order.
     */
    @Query("SELECT f.livenessScore FROM FrameEvent f "
            + "WHERE f.session.id = :sessionId AND f.stage = :stage AND f.livenessScore IS NOT NULL "
            + "ORDER BY f.createdAt ASC, f.id ASC")
    List<Double> findScoresBySessionAndStage(@Param("sessionId") UUID sessionId,
                                             @Param("stage") LivenessStage stage);

    /**
     * PAD flags of the most recent frame events for one session, newest first.
     * Used to confirm a PAD verdict over consecutive frames rather than trusting
     * a single frame — the ONNX model flips to an attack class on ordinary
     * capture conditions, while a real presentation attack is consistent.
     */
    @Query("SELECT f.padFlag FROM FrameEvent f "
            + "WHERE f.session.id = :sessionId AND f.stage = :stage "
            + "ORDER BY f.createdAt DESC, f.id DESC")
    List<Boolean> findRecentPadFlags(@Param("sessionId") UUID sessionId,
                                     @Param("stage") LivenessStage stage,
                                     org.springframework.data.domain.Pageable pageable);

    /**
     * Passive-stage scores of sessions that <em>passed</em>, in capture order —
     * the bona-fide sample for threshold calibration. Attack presentations are
     * excluded by construction: a presentation attack fails the session.
     */
    @Query("SELECT f FROM FrameEvent f "
            + "WHERE f.stage = :stage AND f.livenessScore IS NOT NULL AND f.session.finalResult = true "
            + "ORDER BY f.session.id ASC, f.createdAt ASC, f.id ASC")
    List<FrameEvent> findPassiveScoresForPassedSessions(@Param("stage") LivenessStage stage);

    /**
     * Passive-stage scores of sessions that were rejected as a presentation
     * attack — the (rare, real) attack sample for threshold calibration.
     */
    @Query("SELECT f FROM FrameEvent f "
            + "WHERE f.stage = :stage AND f.livenessScore IS NOT NULL "
            + "AND f.session.failureReason LIKE 'presentation_attack:%' "
            + "ORDER BY f.session.id ASC, f.createdAt ASC, f.id ASC")
    List<FrameEvent> findPassiveScoresForAttackSessions(@Param("stage") LivenessStage stage);
}
