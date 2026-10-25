package io.mosip.liveness.api;

import io.mosip.liveness.dto.ChallengeValidateRequest;
import io.mosip.liveness.dto.ChallengeValidationResult;
import io.mosip.liveness.dto.ChallengeResponse;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.DecisionEngineService;
import io.mosip.liveness.services.ImageUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.opencv.core.Mat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Challenge validation endpoints.
 * Maps to the Python framework's routes/challenges.py.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/challenges")
@RequiredArgsConstructor
public class ChallengesController {

    private final LivenessSessionRepository sessionRepo;
    private final ChallengeRepository challengeRepo;
    private final DecisionEngineService decisionEngine;
    private final ImageUtils imageUtils;
    private final io.mosip.liveness.security.AntiReplayValidator antiReplayValidator;

    @PostMapping("/validate")
    public ChallengeValidationResult validateChallenge(
            @PathVariable UUID sessionId,
            @Valid @RequestBody ChallengeValidateRequest req) {

        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.getStatus() != SessionStatus.ACTIVE
                && session.getStatus() != SessionStatus.PASSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Session is not active (status=" + session.getStatus() + ")");
        }

        ChallengeEntity challenge = challengeRepo.findById(req.getChallengeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Challenge not found for this session"));

        // Ownership before freshness: a challenge id from another session is a
        // 404, not a nonce/timestamp validation error (which would confirm that
        // the id exists).
        if (!challenge.getSession().getId().equals(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Challenge not found for this session");
        }

        antiReplayValidator.validateChallenge(session, challenge, req);
        if (challenge.getStatus() != ChallengeStatus.ISSUED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Challenge is not awaiting validation (status=" + challenge.getStatus() + ")");
        }
        if (session.getStatus() == SessionStatus.PASSED
                && !Boolean.TRUE.equals(challenge.getManual())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Session is not active (status=" + session.getStatus() + ")");
        }

        List<Mat> frames = new ArrayList<>();
        try {
            for (String frameBase64 : req.getFramesBase64()) {
                frames.add(imageUtils.decodeBase64Frame(frameBase64));
            }

            Map<String, Object> result = decisionEngine.processChallengeValidation(
                    session, challenge, frames, imageUtils);
            challengeRepo.save(challenge);
            sessionRepo.save(session);

            // A retry_challenge verdict issues a brand-new challenge. Return that
            // one so the client can immediately validate against it, instead of the
            // challenge that was just resolved (which is now PASSED or FAILED).
            Object newlyIssued = result.get("challenge");
            ChallengeEntity responseChallenge =
                    newlyIssued instanceof ChallengeEntity issued ? issued : challenge;

            String action = (String) result.get("action");
            if (isTerminal(action)) {
                antiReplayValidator.cleanupSession(sessionId);
            }

            ChallengeResponse challengeResp = ChallengeResponse.builder()
                    .id(responseChallenge.getId())
                    .sessionId(sessionId)
                    .challengeType(responseChallenge.getChallengeType())
                    .status(responseChallenge.getStatus())
                    .attemptNumber(responseChallenge.getAttemptNumber())
                    .timeoutMs(responseChallenge.getTimeoutMs())
                    .manual(responseChallenge.getManual())
                    .issuedAt(responseChallenge.getIssuedAt())
                    .completedAt(responseChallenge.getCompletedAt())
                    .build();

            return ChallengeValidationResult.builder()
                    .sessionId(sessionId)
                    .challenge(challengeResp)
                    .passed((Boolean) result.get("passed"))
                    .action(action)
                    .mayRetrySession((Boolean) result.get("mayRetrySession"))
                    .padFlag((Boolean) result.get("padFlag"))
                    .padAttackType((String) result.get("padAttackType"))
                    .message((String) result.get("message"))
                    .build();
        } finally {
            frames.forEach(Mat::release);
        }
    }

    private static boolean isTerminal(String action) {
        return "proceed".equals(action) || "reject".equals(action)
                || "locked".equals(action) || "escalate_to_operator".equals(action)
                || "failed".equals(action) || "manual_check_passed".equals(action)
                || "manual_check_failed".equals(action);
    }
}
