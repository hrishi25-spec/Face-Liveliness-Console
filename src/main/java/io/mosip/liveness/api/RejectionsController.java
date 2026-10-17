package io.mosip.liveness.api;

import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.dto.RejectionEvent;
import io.mosip.liveness.dto.RejectionFeed;
import io.mosip.liveness.dto.SessionListItem;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.enums.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The operator's rejection feed: {@code GET /api/v1/rejections}.
 *
 * <p>Two questions an operator asks after a run is refused — "which sessions
 * failed, and why" and "what did the pipeline reject inside them" — answered
 * together, newest first. The session half is the same projection as
 * {@code GET /api/v1/sessions} ({@link SessionListItem}, mapped by
 * {@link SessionsController#toListItem}), so the two lists can never disagree
 * about what a session row exposes; the event half is the stored audit events,
 * with the same payload the session's own trail carries and nothing added.</p>
 *
 * <p>Read-only and service-wide: it names sessions the caller did not create, so
 * it requires a role — the operator key, or the admin key, which outranks it.
 * {@link RouteInventory#OPERATOR_KEYED} lists it and {@link OperatorKeyFilter}
 * refuses a caller whose headers prove neither, ahead of this handler.</p>
 */
@RestController
@RequestMapping("/api/v1/rejections")
@RequiredArgsConstructor
public class RejectionsController {

    /**
     * The two audit event names a rejection is recorded under, in the order the
     * feed reports them together.
     *
     * <p>{@code AuditEventType} cannot supply both: {@code CHALLENGE_FAILED} is
     * in the enum, but {@code PAD_REJECTED} is not — it is one of the free-form
     * names the HTTP API writes into {@code audit_logs.event_type}
     * ({@code DecisionEngineService}, alongside {@code SESSION_PASSED} and
     * {@code CHALLENGE_ISSUED}). The literals are therefore the contract, not a
     * workaround: they are the strings the pipeline already stored.</p>
     */
    static final List<String> REJECTION_EVENT_TYPES = List.of("PAD_REJECTED", "CHALLENGE_FAILED");

    private final LivenessSessionRepository sessionRepo;
    private final AuditLogRepository auditLogRepo;

    /**
     * The recently rejected sessions and the rejection events recorded for them,
     * each newest first and each at most {@code limit} rows.
     *
     * <p>Both halves are narrowed and ordered in SQL
     * ({@link LivenessSessionRepository#findRecent},
     * {@link AuditLogRepository#findRecentByEventTypes}), so the page is built
     * from the newest rows rather than read from a full table. The two halves
     * carry the same limit independently: a rejection is routinely one failed
     * session <em>and</em> one or more stored events, so a shared cap would
     * return an arbitrary split of the two.</p>
     *
     * <p>{@code readOnly} because mapping the event rows reads their session id
     * off a lazy association; the join fetch in the query already brings the
     * session along, and this keeps the two reads inside one transaction if that
     * ever stops being true. Nothing here writes.</p>
     */
    @GetMapping
    @Transactional(readOnly = true)
    public RejectionFeed rejections(@RequestParam(required = false) Integer limit) {
        int page = FeedLimit.checked(limit);
        PageRequest firstPage = PageRequest.of(0, page);

        List<SessionListItem> sessions = sessionRepo
                .findRecent(null, SessionStatus.FAILED, firstPage).stream()
                .map(SessionsController::toListItem)
                .toList();

        List<RejectionEvent> events = auditLogRepo
                .findRecentByEventTypes(REJECTION_EVENT_TYPES, firstPage).stream()
                .map(RejectionsController::toEvent)
                .toList();

        return RejectionFeed.builder()
                .limit(page)
                .sessions(sessions)
                .events(events)
                .build();
    }

    /**
     * One stored rejection event. The {@code details} map is passed through
     * unchanged — it is the audit record's own payload, and rewriting it here
     * would make the feed disagree with the session trail about the same row.
     */
    private static RejectionEvent toEvent(AuditLog entry) {
        return RejectionEvent.builder()
                .id(entry.getId())
                .eventType(entry.getEventType())
                .sessionId(entry.getSession() != null ? entry.getSession().getId() : null)
                .details(entry.getDetails())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}
