package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LivenessSessionRepository extends JpaRepository<LivenessSession, UUID> {

    long countByStatus(SessionStatus status);

    long countByWorkflowType(WorkflowType workflowType);

    @Query("SELECT COALESCE(SUM(s.retryCount), 0) FROM LivenessSession s")
    long sumRetryCount();

    @Query("SELECT COUNT(s) FROM LivenessSession s WHERE s.failureReason LIKE :pattern")
    long countByFailureReasonLike(@Param("pattern") String pattern);

    /**
     * One page of the session feed the operator reads — newest first, with each
     * filter applied only when it is present (the caller passes {@code null} to
     * leave one out, which is what an absent query parameter means).
     *
     * <p>The ordering is SQL's, and the page size is {@link Pageable}'s, so the
     * limit selects the newest rows rather than loading the table and cutting a
     * window out of it. {@code liveness_sessions} grows one row per verification
     * and keeps every frame event beside it, so a feed that pulled every row to
     * return fifty would get slower with every session the deployment has ever
     * run.</p>
     *
     * <p>{@code LIMIT}/{@code OFFSET} from a single-page {@code PageRequest} is
     * what bounds the result; the method returns a {@link List} rather than a
     * {@code Page} so no second counting query runs for a feed nobody paginates
     * beyond the first page.</p>
     *
     * @param workflowType optional workflow filter
     * @param status       optional status filter
     * @param pageable     the page to return; the caller always asks for the
     *                     first page, with the request's limit as its size
     */
    @Query("SELECT s FROM LivenessSession s "
            + "WHERE (:workflowType IS NULL OR s.workflowType = :workflowType) "
            + "AND (:status IS NULL OR s.status = :status) "
            + "ORDER BY s.createdAt DESC")
    List<LivenessSession> findRecent(@Param("workflowType") WorkflowType workflowType,
                                     @Param("status") SessionStatus status,
                                     Pageable pageable);

    /**
     * Row lock held for the duration of one frame/challenge decision
     * ({@code SELECT ... FOR UPDATE}). Two concurrent submissions for the same
     * session would otherwise both observe the passive stage and race: one
     * marking the session PASSED while the other issues a challenge — leaving a
     * challenge ISSUED on a session that already passed, or a pass recorded
     * without the required active verification. Must be called inside a
     * transaction (the decision methods are {@code @Transactional}); callers
     * fall back to the already-loaded instance when the row is unknown (unit
     * tests with detached sessions).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LivenessSession s WHERE s.id = :id")
    Optional<LivenessSession> findByIdForUpdate(@Param("id") UUID id);
}
