package io.mosip.liveness.api;

import io.mosip.liveness.dto.OperationalMetrics;
import io.mosip.liveness.metrics.PipelineTimers;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.audit.MetricsSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metrics endpoints.
 * Maps to the Python framework's routes/metrics.py.
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final LivenessSessionRepository sessionRepo;
    private final FrameEventRepository frameEventRepo;
    private final ChallengeRepository challengeRepo;
    private final RateLimitCounters rateLimitCounters;
    private final MetricsCollector metricsCollector;

    @GetMapping
    public OperationalMetrics getOperationalMetrics() {
        long totalSessions = sessionRepo.count();
        long passed = sessionRepo.countByStatus(SessionStatus.PASSED);
        long failed = sessionRepo.countByStatus(SessionStatus.FAILED);
        long active = sessionRepo.countByStatus(SessionStatus.ACTIVE);

        long escalated = frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE);
        long totalFrames = frameEventRepo.count();
        long totalChallenges = challengeRepo.count();
        long failedChallenges = challengeRepo.countByStatus(ChallengeStatus.FAILED);
        long totalRetries = sessionRepo.sumRetryCount();
        long padRejections = sessionRepo.countByFailureReasonLike("presentation_attack:%");

        // Limiter tallies are in-memory counters in the filter, so a tester can
        // confirm the limiter is (or is not) biting without reading container
        // logs. Reported separately from the DB-backed session metrics because
        // they reset with the process.
        RateLimitCounters.Counts limiter = rateLimitCounters.snapshot();

        // Where the per-frame time actually goes, same in-memory caveat.
        java.util.Map<String, PipelineTimers.PhaseTiming> pipeline = PipelineTimers.snapshot();

        MetricsSnapshot metricsSnapshot = metricsCollector.snapshot();
        return OperationalMetrics.builder()
                .totalSessions((int) totalSessions)
                .passedSessions((int) passed)
                .failedSessions((int) failed)
                .activeSessions((int) active)
                .passRate(safeDiv(passed, totalSessions))
                .avgPassiveToActiveEscalationRate(safeDiv(escalated, totalSessions))
                .avgFramesPerSession(safeDiv(totalFrames, totalSessions))
                .avgChallengesPerSession(safeDiv(totalChallenges, totalSessions))
                .livenessRetryRate(safeDiv(totalRetries, totalSessions))
                .livenessFailureRate(safeDiv(failed, totalSessions))
                .padRejectionRate(safeDiv(padRejections, totalSessions))
                .printPhotoApcer(metricsSnapshot.printPhotoApcer())
                .screenReplayApcer(metricsSnapshot.screenReplayApcer())
                .videoReplayApcer(metricsSnapshot.videoReplayApcer())
                .otherApcer(metricsSnapshot.otherApcer())
                .pipelineTimings(pipeline)
                .rateLimitAllowedRequests((int) limiter.allowed())
                .rateLimitedRequests((int) limiter.rejected())
                .rateLimitedSessionCreate((int) limiter.sessionCreateRejected())
                .rateLimitedFrames((int) limiter.framesRejected())
                .rateLimitedOperatorKey((int) limiter.operatorKeyRejected())
                .rateLimitRejectionRate(round4(limiter.rejectionRate()))
                .build();
    }

    private double safeDiv(long a, long b) {
        return b == 0 ? 0.0 : round4((double) a / b);
    }

    /** Same 4-decimal rounding the other rates use, so the payload stays tidy. */
    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
