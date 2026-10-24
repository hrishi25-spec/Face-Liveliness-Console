package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.config.EffectivePolicyValidator;
import io.mosip.liveness.dto.SessionCreateRequest;
import io.mosip.liveness.dto.SessionListItem;
import io.mosip.liveness.dto.SessionResponse;
import io.mosip.liveness.dto.SessionSummary;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.AuthRole;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Session lifecycle endpoints, plus the operator's session feed.
 * Maps to the Python framework's routes/sessions.py.
 *
 * <p>The lifecycle routes are client-facing and, once a session declares a
 * {@code subjectRef}, reachable only by its owner ({@link SessionOwnershipFilter}).
 * The collection read ({@code GET /api/v1/sessions}) is neither: it lists other
 * people's sessions, so it requires the operator key instead
 * ({@link RouteInventory#OPERATOR_KEYED}, enforced by {@link OperatorKeyFilter}).
 * Keeping both on one controller is deliberate — they are the same resource,
 * and the split is the verb, not the path.</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionsController {

    private final LivenessSessionRepository sessionRepo;
    private final FrameEventRepository frameEventRepo;
    private final ChallengeRepository challengeRepo;
    private final ConfigService configService;
    private final EffectivePolicyValidator effectivePolicyValidator;
    private final io.mosip.liveness.security.AntiReplayValidator antiReplayValidator;
    private final io.mosip.liveness.services.LivenessEvidenceVerifier evidenceVerifier;

    /**
     * The operator's session feed: newest first, narrowable by workflow and
     * status, one page at a time.
     *
     * <p>Anonymous by design — a session's owner is named by
     * {@code subjectRef}, which is never part of a row here: the feed is for an
     * operator triaging the deployment, and the owner reference is the one
     * field that would turn a list into an identity lookup. The key is checked
     * ahead of this handler by {@link OperatorKeyFilter}, so nothing is checked
     * here; a missing or wrong key has already been answered 401.</p>
     *
     * <p>Filters are applied in SQL with the page limit
     * ({@link LivenessSessionRepository#findRecent}), so an absent filter is an
     * absent predicate rather than a post-filter over a whole-table read. Both
     * parameters are enum-typed: an unknown value is a 400 from the global
     * handler, and a blank one ({@code ?workflowType=}) is Spring's “no
     * filter”, which is what a console that always renders both fields sends.</p>
     */
    @GetMapping
    public List<SessionListItem> listSessions(
            @RequestParam(required = false) WorkflowType workflowType,
            @RequestParam(required = false) SessionStatus status,
            @RequestParam(required = false) Integer limit) {
        int page = FeedLimit.checked(limit);
        return sessionRepo.findRecent(workflowType, status, PageRequest.of(0, page)).stream()
                .map(SessionsController::toListItem)
                .toList();
    }

    /**
     * Opens a session for the user type the caller is entitled to.
     *
     * <p>The user type is not a client choice once somebody has signed in: a
     * session created with a session cookie runs as that account's role
     * ({@link AuthRole#SUPERVISOR} → {@code SUPERVISOR}, otherwise
     * {@code OPERATOR}), and a body that names a different type is refused with
     * 403 rather than quietly downgraded. Before this rule, a client could claim
     * {@code RESIDENT} while authenticating a supervisor and receive the most
     * forgiving policy — 0.80, one challenge, three retries — for a flow the
     * account is supposed to be measured against at 0.85 with two challenges.</p>
     *
     * <p>An anonymous caller is unchanged: the registration client's resident
     * capture has no account to derive a type from, and this service has no
     * resident identity provider, so the host application keeps deciding there.</p>
     */
    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@Valid @RequestBody SessionCreateRequest req,
                                                         HttpServletRequest request) {
        WorkflowType workflowType = resolveWorkflowType(request, req.getWorkflowType());

        // Resolve the user type's policy once, validate it, and freeze it on the
        // session. Doing this here (rather than per frame) means the operating
        // point is chosen at the start and cannot drift mid-session, and an
        // invalid configuration fails creation rather than being frozen silently.
        EffectivePolicy policy = configService.getEffectivePolicy(
                configService.toCoreWorkflow(workflowType));
        effectivePolicyValidator.validate(policy);

        LivenessSession session = LivenessSession.builder()
                .workflowType(workflowType)
                .deviceId(req.getDeviceId())
                .subjectRef(req.getSubjectRef())
                .online(req.getOnline() != null ? req.getOnline() : true)
                .status(SessionStatus.ACTIVE)
                .retryCount(0)
                .nonce(io.mosip.liveness.security.AntiReplayValidator.generateNonce())
                .policySnapshot(policy)
                .policySnapshotAt(OffsetDateTime.now())
                .build();
        sessionRepo.save(session);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
    }

    /**
     * The user type this request may open, from the caller's role when it has
     * one and from the body when it does not.
     *
     * <p>A role is read once, here, rather than trusted from the request body
     * later: the policy is frozen at creation, so this is the only point where
     * swapping the type would change what a session is measured against.</p>
     */
    private static WorkflowType resolveWorkflowType(HttpServletRequest request, WorkflowType claimed) {
        Optional<AuthPrincipal> caller = AuthGate.principal(request);
        if (caller.isEmpty()) {
            if (claimed == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflow_type is required");
            }
            return claimed;
        }

        WorkflowType fromRole = caller.get().holds(AuthRole.SUPERVISOR)
                ? WorkflowType.SUPERVISOR
                : WorkflowType.OPERATOR;
        if (claimed != null && claimed != fromRole) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, AuthRefusals.USER_TYPE_FROM_ACCOUNT);
        }
        return fromRole;
    }

    /**
     * Reads are session-scoped too: the response carries the session's identity,
     * stage and failure reason, which is exactly what an owner wants kept private.
     * Ownership is {@link SessionOwnershipFilter}'s, ahead of this handler.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionResponse> getSession(
            @PathVariable java.util.UUID sessionId) {
        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        return ResponseEntity.ok(toResponse(session));
    }

    /**
     * Verifies signed liveness evidence produced by an online client gate (spec §2 LivenessEvidence).
     * Validates session nonce binding, timing window, and challenge completion before acceptance.
     */
    @PostMapping("/{sessionId}/evidence")
    public ResponseEntity<io.mosip.liveness.dto.EvidenceVerificationResponse> verifyEvidence(
            @PathVariable java.util.UUID sessionId,
            @Valid @RequestBody io.mosip.liveness.dto.EvidenceVerificationRequest req) {
        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        return ResponseEntity.ok(evidenceVerifier.verify(session, req));
    }

    /** Closing is an action on somebody's session; the filter has already refused a stranger. */
    @PostMapping("/{sessionId}/close")
    public ResponseEntity<SessionSummary> closeSession(
            @PathVariable java.util.UUID sessionId) {
        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.getStatus() == SessionStatus.ACTIVE) {
            session.setStatus(SessionStatus.EXPIRED);
            session.setClosedAt(OffsetDateTime.now());
            sessionRepo.save(session);
        }

        antiReplayValidator.cleanupSession(sessionId);

        long durationMs = 0;
        if (session.getClosedAt() != null && session.getCreatedAt() != null) {
            durationMs = java.time.Duration.between(session.getCreatedAt(), session.getClosedAt()).toMillis();
        }

        return ResponseEntity.ok(SessionSummary.builder()
                .id(session.getId())
                .workflowType(session.getWorkflowType())
                .status(session.getStatus())
                .finalResult(session.getFinalResult())
                .failureReason(session.getFailureReason())
                .totalFramesEvaluated((int) frameEventRepo.countBySessionId(sessionId))
                .totalChallengesIssued((int) challengeRepo.countBySessionId(sessionId))
                .retryCount(session.getRetryCount())
                .durationMs(durationMs)
                .build());
    }

    /**
     * The session-row projection the two operator feeds share: the rejections
     * feed's failed rows are the same shape as this list's, so one mapper keeps a
     * field from appearing in one answer and not the other.
     *
     * <p>Everything else on the entity — {@code subjectRef}, the frozen policy
     * snapshot, the stage timestamps — is left out on purpose; see
     * {@link SessionListItem}.</p>
     */
    static SessionListItem toListItem(LivenessSession s) {
        return SessionListItem.builder()
                .id(s.getId())
                .workflowType(s.getWorkflowType())
                .deviceId(s.getDeviceId())
                .status(s.getStatus())
                .currentStage(s.getCurrentStage())
                .retryCount(s.getRetryCount())
                .finalResult(s.getFinalResult())
                .failureReason(s.getFailureReason())
                .createdAt(s.getCreatedAt())
                .closedAt(s.getClosedAt())
                .build();
    }

    private SessionResponse toResponse(LivenessSession s) {
        return SessionResponse.builder()
                .id(s.getId())
                .workflowType(s.getWorkflowType())
                .policy(s.getPolicySnapshot())
                .deviceId(s.getDeviceId())
                .status(s.getStatus())
                .currentStage(s.getCurrentStage())
                .retryCount(s.getRetryCount())
                .online(s.getOnline())
                .finalResult(s.getFinalResult())
                .failureReason(s.getFailureReason())
                .nonce(s.getNonce())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .closedAt(s.getClosedAt())
                .build();
    }
}
