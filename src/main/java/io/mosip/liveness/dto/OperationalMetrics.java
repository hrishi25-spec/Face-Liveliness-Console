package io.mosip.liveness.dto;

import io.mosip.liveness.metrics.PipelineTimers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Anonymized operational metrics.
 * Maps to the Python framework's OperationalMetrics schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationalMetrics {

    private Integer totalSessions;
    private Integer passedSessions;
    private Integer failedSessions;
    private Integer activeSessions;
    private Double passRate;
    private Double avgPassiveToActiveEscalationRate;
    private Double avgFramesPerSession;
    private Double avgChallengesPerSession;
    private Double livenessRetryRate;
    private Double livenessFailureRate;
    private Double padRejectionRate;

    /**
     * Per-species APCER (Attack Presentation Classification Error Rate):
     * proportion of attack presentations classified as bona fide.
     * Values in [0,1]; higher means more vulnerable to that attack type.
     */
    private Double printPhotoApcer;
    private Double screenReplayApcer;
    private Double videoReplayApcer;
    private Double otherApcer;

    /**
     * Rate-limiter tallies since process start (in-memory, so they reset on
     * restart — unlike the session counters above, which come from the
     * database). A non-zero {@code rateLimitedRequests} means callers are being
     * refused; the split shows which rule is biting.
     */
    /**
     * Per-frame decision-path timings since process start, keyed by phase
     * ({@code decode}, {@code facedetect}, {@code onnxscore},
     * {@code heuristicscore}, {@code padheuristic}, {@code padonnx},
     * {@code total}), each with count / mean / max / p99 in milliseconds and
     * its share of the measured phases.
     *
     * <p>In-memory like the rate-limiter tallies, so they reset on restart.
     * Phases that never ran are absent from the map rather than reported as
     * zero, so "heuristic fallback is in use" is distinguishable from "this
     * build was never scored".</p>
     */
    private Map<String, PipelineTimers.PhaseTiming> pipelineTimings;

    private Integer rateLimitAllowedRequests;
    private Integer rateLimitedRequests;
    private Integer rateLimitedSessionCreate;
    private Integer rateLimitedFrames;
    private Integer rateLimitedOperatorKey;
    private Double rateLimitRejectionRate;
}
