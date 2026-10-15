package io.mosip.liveness.crud;

import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.enums.WorkflowType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Operator-level audit feed: newest first. {@code session IS NULL} keeps
     * these out of every session trail while still being one table (and one
     * timestamp ordering) for operators to read.
     *
     * <p>{@code Pageable} is the page size (always a single page) so the limit
     * is applied by the query — the feed must never pull the whole audit table
     * to return the newest few entries.</p>
     */
    List<AuditLog> findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(String eventType, Pageable pageable);

    /**
     * The same feed, narrowed to one workflow in the database rather than in
     * the browser. This is the query that makes the column worth having: the
     * audit table grows one row per frame decision, so returning every row to
     * filter client-side means paging through the whole pipeline history to
     * answer a question about policy edits.
     *
     * <p>Backed by {@code idx_audit_logs_config_feed}.</p>
     */
    List<AuditLog> findByEventTypeAndWorkflowTypeAndSessionIsNullOrderByCreatedAtDesc(
            String eventType, WorkflowType workflowType, Pageable pageable);

    /**
     * The rejection events for the operator feed: the named event types only,
     * newest first, bounded by the page size.
     *
     * <p>Narrowed in SQL rather than in the caller, for the same reason as the
     * config feed above — this table grows one row per frame decision, so
     * filtering after the fact would read the whole pipeline history to answer a
     * question about the last fifty rejections. The {@code IN} covers both
     * rejections under one ordering, so {@code PAD_REJECTED} and
     * {@code CHALLENGE_FAILED} interleave by time instead of arriving as two
     * lists a reader has to merge.</p>
     *
     * <p>Unlike the config feed this is not {@code session IS NULL}: rejection
     * events always belong to the session that was rejected, and the feed has to
     * name it so a reader can drill into that session's own trail. The session is
     * a lazy association, so it is fetched with the row ({@code LEFT JOIN FETCH},
     * not a second query per event) — one page of the feed stays one query, and a
     * caller can read the id from a detached entity. {@code LEFT} rather than an
     * inner join so a row whose session is gone is still reported rather than
     * silently dropped from the feed.</p>
     */
    @Query("SELECT a FROM AuditLog a LEFT JOIN FETCH a.session "
            + "WHERE a.eventType IN :eventTypes ORDER BY a.createdAt DESC")
    List<AuditLog> findRecentByEventTypes(@Param("eventTypes") Collection<String> eventTypes,
                                          Pageable pageable);

    /**
     * The newest entry that is part of a hash chain, for linking the next one
     * onto it. Entries written before {@code V9} have no hash and are skipped,
     * so the chain continues across the migration boundary instead of
     * restarting on a NULL.
     */
    Optional<AuditLog> findFirstByEventTypeAndEntryHashIsNotNullOrderByCreatedAtDesc(String eventType);

    /**
     * The whole chain, oldest first, for verification. Paged on purpose: this
     * walk must never be truncated, or a break past the page boundary would
     * read as "chain intact".
     */
    List<AuditLog> findByEventTypeAndEntryHashIsNotNullOrderByCreatedAtAsc(String eventType);
}
