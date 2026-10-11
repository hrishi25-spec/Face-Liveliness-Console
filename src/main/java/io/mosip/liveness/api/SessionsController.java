package io.mosip.liveness.api;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.config.EffectivePolicyValidator;
import io.mosip.liveness.dto.SessionCreateRequest;
import io.mosip.liveness.dto.SessionResponse;
import io.mosip.liveness.dto.SessionSummary;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.ConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * Session lifecycle endpoints.
 * Maps to the Python framework's routes/sessions.py.
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

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@Valid @RequestBody SessionCreateRequest req) {
        // Resolve the user type's policy once, validate it, and freeze it on the
        // session. Doing this here (rather than per frame) means the operating
        // point is chosen at the start and cannot drift mid-session, and an
        // invalid configuration fails creation rather than being frozen silently.
        EffectivePolicy policy = configService.getEffectivePolicy(
                configService.toCoreWorkflow(req.getWorkflowType()));
        effectivePolicyValidator.validate(policy);

        LivenessSession session = LivenessSession.builder()
                .workflowType(req.getWorkflowType())
                .deviceId(req.getDeviceId())
                .subjectRef(req.getSubjectRef())
                .online(req.getOnline() != null ? req.getOnline() : true)
                .status(SessionStatus.ACTIVE)
                .retryCount(0)
                .policySnapshot(policy)
                .policySnapshotAt(OffsetDateTime.now())
                .build();
        sessionRepo.save(session);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
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
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .closedAt(s.getClosedAt())
                .build();
    }
}
