package io.mosip.liveness.api;

import io.mosip.liveness.metrics.PipelineTimers;

import io.mosip.liveness.diagnostics.DiagnosticsService;
import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.dto.FrameSubmitRequest;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.DecisionEngineService;
import io.mosip.liveness.services.ImageUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.opencv.core.Mat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Frame submission endpoints.
 * Maps to the Python framework's routes/frames.py.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/frames")
@RequiredArgsConstructor
public class FramesController {

    private final LivenessSessionRepository sessionRepo;
    private final DecisionEngineService decisionEngine;
    private final ImageUtils imageUtils;
    private final DiagnosticsService diagnostics;
    private final io.mosip.liveness.security.AntiReplayValidator antiReplayValidator;

    /**
     * Frames are the session's biometrics: {@link SessionOwnershipFilter} has
     * already refused anyone but the session's owner, before a frame is decoded
     * or scored.
     */
    @PostMapping
    public FrameProcessResult submitFrame(
            @PathVariable java.util.UUID sessionId,
            @Valid @RequestBody FrameSubmitRequest req) {

        LivenessSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Session is not active (status=" + session.getStatus() + ")");
        }

        // Timed end to end so the phases have something to be compared against —
        // see PipelineTimers.TOTAL. Includes the DB round trip, which is why it
        // is deliberately larger than the sum of the pipeline phases.
        long diagnosticsStart = System.nanoTime();
        FrameProcessResult result = PipelineTimers.timed(PipelineTimers.TOTAL, () -> {
            Mat frame = imageUtils.decodeBase64Frame(req.getFrameBase64());
            try {
                antiReplayValidator.validateFrame(session, req, frame);
                return decisionEngine.processFrame(session, frame, imageUtils);
            } finally {
                frame.release();
                sessionRepo.save(session);
            }
        });
        // Diagnostic mode (opt-in, local): the sample carries only the decision
        // DTO — scores, quality, flag, action — plus this frame's elapsed time.
        // Disabled, the call is a no-op and nothing is retained (spec §10).
        diagnostics.recordFrame(result, System.nanoTime() - diagnosticsStart);
        // A terminal verdict ends this capture stream. Release its replay
        // bookkeeping now instead of waiting for an explicit close (a client can
        // stop after proceed/reject and never call close).
        if (isTerminal(result.getAction())) {
            antiReplayValidator.cleanupSession(sessionId);
        }
        return result;
    }

    private static boolean isTerminal(String action) {
        return "proceed".equals(action) || "reject".equals(action)
                || "locked".equals(action) || "escalate_to_operator".equals(action)
                || "failed".equals(action);
    }
}
