package io.mosip.liveness.api;

import io.mosip.liveness.dto.ChallengeResponse;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.DecisionEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Manual active-liveness activation.
 *
 * <p>Active challenges normally run only when the passive stage escalates, so a
 * session whose passive median is at or above the threshold passes without one.
 * This endpoint starts a single challenge on such a session on demand — the
 * console's "Run active liveness" button drives it. The issued challenge is
 * marked {@code manual} and is validated as an extra check: its verdict is
 * recorded on the challenge row and in the audit trail, but it never rewrites
 * the session's PASSED verdict (see
 * {@link DecisionEngineService#startActiveLiveness}).</p>
 *
 * <p>Fail-closed refusals are 409s: a session that has not passed, a workflow
 * with active liveness disabled, or a workflow with no challenge types. An
 * already-open challenge is returned unchanged, so a retry cannot issue a
 * second one.</p>
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/active-liveness")
@RequiredArgsConstructor
public class ActiveLivenessController {

    private final LivenessSessionRepository sessionRepo;
    private final DecisionEngineService decisionEngine;

    /**
     * An extra check is an action on somebody's session like any other:
     * {@link SessionOwnershipFilter} has already refused a stranger, so no
     * challenge is issued on a call that is not the owner's.
     */
    @PostMapping
    public ResponseEntity<ChallengeResponse> startActiveLiveness(
            @PathVariable UUID sessionId) {
        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        ChallengeEntity challenge = decisionEngine.startActiveLiveness(session);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(sessionId, challenge));
    }

    private ChallengeResponse toResponse(UUID sessionId, ChallengeEntity challenge) {
        return ChallengeResponse.builder()
                .id(challenge.getId())
                .sessionId(sessionId)
                .challengeType(challenge.getChallengeType())
                .status(challenge.getStatus())
                .attemptNumber(challenge.getAttemptNumber())
                .timeoutMs(challenge.getTimeoutMs())
                .manual(challenge.getManual())
                .issuedAt(challenge.getIssuedAt())
                .completedAt(challenge.getCompletedAt())
                .build();
    }
}
