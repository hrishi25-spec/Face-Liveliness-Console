package io.mosip.liveness.engine;

import io.mosip.liveness.audit.AuditEvent;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.challenge.ChallengeEvaluators;
import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.CombinedLivenessScore;
import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline face liveness + PAD engine.
 *
 * <p>Flow: N consecutive passive frames are scored; the temporal median is
 * compared against the configured threshold. Median >= threshold (and no PAD
 * flag) proceeds. Below threshold escalates automatically to Stage-2
 * challenge-response with engine-selected randomized challenges. During
 * challenges, each frame is evaluated by three subsystems: PAD, passive
 * liveness scorer (re-evaluation), and active challenge evaluator. Any PAD
 * hit blocks the session terminally.</p>
 *
 * <h3>Threading contract</h3>
 * <p>Multi-session concurrent access is safe. A single session's frames
 * must be pushed from one thread only.</p>
 */
public final class FaceLivenessEngine implements LivenessPipeline {

    private final LivenessConfig config;
    private final LivenessBackend backend;
    private final AuditLogger audit;
    private final MetricsCollector metrics;
    private final Clock clock;
    private final Map<String, LivenessSession> sessions = new ConcurrentHashMap<>();

    public FaceLivenessEngine(LivenessConfig config, LivenessBackend backend) {
        this(config, backend, AuditLogger.noop(), new MetricsCollector(), Clock.systemUTC(), Map.of());
    }

    /** v3: accepts backend options forwarded to {@code backend.initialize()}. */
    public FaceLivenessEngine(LivenessConfig config, LivenessBackend backend,
                              Map<String, String> backendOptions) {
        this(config, backend, AuditLogger.noop(), new MetricsCollector(), Clock.systemUTC(), backendOptions);
    }

    public FaceLivenessEngine(LivenessConfig config, LivenessBackend backend,
                              AuditLogger audit, MetricsCollector metrics, Clock clock) {
        this(config, backend, audit, metrics, clock, Map.of());
    }

    public FaceLivenessEngine(LivenessConfig config, LivenessBackend backend,
                              AuditLogger audit, MetricsCollector metrics, Clock clock,
                              Map<String, String> backendOptions) {
        this.config = Objects.requireNonNull(config);
        this.backend = Objects.requireNonNull(backend);
        this.audit = Objects.requireNonNull(audit);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
        config.validate();
        try {
            backend.initialize(backendOptions != null ? backendOptions : Map.of());
        } catch (RuntimeException e) {
            throw new LivenessException(LivenessErrorCode.DEVICE_CONNECTION_FAILURE,
                    "liveness backend initialization failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ session

    @Override
    public String initSession(WorkflowType workflow) {
        Objects.requireNonNull(workflow, "workflow");
        String id = UUID.randomUUID().toString();
        EffectivePolicy policy = config.effectivePolicy(workflow);
        sessions.put(id, new LivenessSession(id, workflow, policy, clock.millis()));
        audit.log(AuditEvent.of(clock.millis(), id, workflow, AuditEventType.SESSION_STARTED)
                .field("policy", policy.summary())
                .field("backend", backend.id()));
        return id;
    }

    // ------------------------------------------------------------------ frames

    @Override
    public FrameAssessment pushFrame(String sessionId, Frame frame) {
        long startNanos = System.nanoTime();
        LivenessSession s = requireSession(sessionId);

        // G3: session duration timeout — check on EVERY frame, even skipped ones
        if (s.isSessionTimedOut(clock.millis()) && !s.isTerminal() && s.state() != LivenessSession.State.PASSED) {
            s.fail(LivenessErrorCode.SESSION_TIMEOUT, SessionSummary.Outcome.FAILED_SESSION_TIMEOUT);
            metrics.recordSessionEnd(true, null, false); // Timeout is not a PAD attack
            audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(), AuditEventType.INTERNAL_ERROR)
                    .field("code", "SESSION_TIMEOUT"));
            return FrameAssessment.sessionTimedOut();
        }

        if (s.state() == LivenessSession.State.PASSED) {
            return s.bypass() ? FrameAssessment.bypassed() : FrameAssessment.passed(s.lastMedianScore());
        }
        if (s.isTerminal()) {
            throw new LivenessException(LivenessErrorCode.INVALID_STATE,
                    "session already ended with outcome " + s.outcome());
        }

        if (!s.policy().livenessEnabled()) {
            s.markBypassed();
            return FrameAssessment.bypassed();
        }

        // G7: frame sampling — skip frames per rate, but always check timeout above
        if (!shouldProcessFrame(s)) {
            return FrameAssessment.challengeInProgress();
        }

        if (s.state() == LivenessSession.State.IN_CHALLENGE) {
            // v3: During IN_CHALLENGE, evaluate each frame with full triple pipeline
            return pushChallengeFrame(s, sessionId, frame, startNanos);
        }

        // G7: check challenge deadline for timeout even on sampled frames
        checkChallengeDeadline(s);
        if (s.state() == LivenessSession.State.IN_CHALLENGE) {
            return FrameAssessment.challengeInProgress();
        }
        if (s.isTerminal()) {
            return FrameAssessment.failed(s.errorCode(), s.lastMedianScore());
        }
        if (s.escalated()) {
            return FrameAssessment.escalated(s.lastMedianScore());
        }

        try {
            FaceSignals signals = safely(() -> backend.analyzeFrame(frame));

            if (signals.faceCount() == 0) {
                return reject(s, LivenessErrorCode.FACE_NOT_DETECTED);
            }
            if (signals.faceCount() > 1) {
                return reject(s, LivenessErrorCode.MULTIPLE_FACES_DETECTED);
            }
            if (signals.qualityScore() < s.policy().minFaceQuality()) {
                return reject(s, LivenessErrorCode.POOR_FACE_QUALITY);
            }

            PadVerdict pad = safely(() -> backend.assessPad(frame, signals));
            if (pad.attackDetected()) {
                return blockForPad(s, pad);
            }

            double score = safely(() -> backend.scorePassiveLiveness(frame, signals));
            metrics.recordProcessingTime((System.nanoTime() - startNanos) / 1_000_000.0);

            if (s.state() == LivenessSession.State.SCORING) {
                s.addScore(score);
                if (s.enoughPassiveFrames()) {
                    return decidePassiveStage(s);
                }
            } else if (s.escalated()) {
                return FrameAssessment.escalated(s.lastMedianScore());
            }
            return FrameAssessment.scoring(score);
        } catch (LivenessException e) {
            metrics.recordProcessingTime((System.nanoTime() - startNanos) / 1_000_000.0);
            audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(), AuditEventType.INTERNAL_ERROR)
                    .field("code", e.errorCode().name()));
            throw e;
        }
    }

    /**
     * v3: Frame-by-frame evaluation during the active challenge window.
     * Runs triple pipeline: PAD + passive re-evaluation + active challenge evaluator.
     */
    private FrameAssessment pushChallengeFrame(LivenessSession s, String sessionId,
                                               Frame frame, long startNanos) {
        checkChallengeDeadline(s);
        if (s.state() == LivenessSession.State.IN_CHALLENGE) {
            // Still in challenge — evaluate the frame
        } else if (s.isTerminal()) {
            return FrameAssessment.failed(s.errorCode(), s.lastMedianScore());
        } else {
            // Timeout moved us to ESCALATED
            return FrameAssessment.escalated(s.lastMedianScore());
        }

        try {
            FaceSignals signals = safely(() -> backend.analyzeFrame(frame));

            if (signals.faceCount() == 0) {
                return reject(s, LivenessErrorCode.FACE_NOT_DETECTED);
            }
            if (signals.faceCount() > 1) {
                return reject(s, LivenessErrorCode.MULTIPLE_FACES_DETECTED);
            }

            // 1. PAD check — runs on every frame, active or passive
            PadVerdict pad = safely(() -> backend.assessPad(frame, signals));
            if (pad.attackDetected()) {
                return blockForPad(s, pad);
            }

            // 2. Passive re-evaluation (G1) — run passive scorer even during active challenge
            double passiveScore = safely(() -> backend.scorePassiveLiveness(frame, signals));
            s.addActivePassiveScore(passiveScore);

            // 3. Active challenge evaluator (G2) — frame-by-frame with progress
            Challenge challenge = s.currentChallenge();
            ChallengeEvaluators.FrameChallengeResult challengeResult =
                    ChallengeEvaluators.evaluateFrame(challenge, signals,
                            s.actionDetectedThisChallenge() ? 1 : 0);
            s.recordChallengeProgress(challengeResult.progress());

            // 4. Combined score (G10)
            double activeComponent = challengeResult.actionDetected() ? 1.0 : 0.0;
            double combined = s.policy().combinedPassiveWeight() * passiveScore
                    + s.policy().combinedActiveWeight() * activeComponent;
            CombinedLivenessScore combinedScore = new CombinedLivenessScore(
                    passiveScore, activeComponent, combined);

            metrics.recordProcessingTime((System.nanoTime() - startNanos) / 1_000_000.0);

            // 5. Check passive re-evaluation failure (G1)
            if (s.passiveScoreFailedDuringActive(s.policy().passiveThresholdActive())) {
                s.fail(LivenessErrorCode.ACTIVE_REEVAL_FAILED,
                        SessionSummary.Outcome.FAILED_LIVENESS);
                metrics.recordSessionEnd(true, null, false);
                audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(), AuditEventType.LIVENESS_FAILED)
                        .field("reason", "passive_reeval_during_active")
                        .field("passiveScore", fmt(passiveScore)));
                return FrameAssessment.failed(LivenessErrorCode.ACTIVE_REEVAL_FAILED, passiveScore);
            }

            return FrameAssessment.challengeEvaluated(challengeResult.progress(), combinedScore);
        } catch (LivenessException e) {
            metrics.recordProcessingTime((System.nanoTime() - startNanos) / 1_000_000.0);
            audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(), AuditEventType.INTERNAL_ERROR)
                    .field("code", e.errorCode().name()));
            throw e;
        }
    }

    // G7: frame sampling — process 1 in N frames based on config (per-session counter)
    private boolean shouldProcessFrame(LivenessSession s) {
        return s.incrementAndCheckFrameSampling();
    }

    private FrameAssessment decidePassiveStage(LivenessSession s) {
        double median = s.medianScore();
        double threshold = s.policy().passiveThreshold();
        var decision = LivenessDecisionLogic.decidePassive(median, threshold, false);

        audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(), AuditEventType.FRAME_SCORED)
                .field("median", fmt(median))
                .field("threshold", threshold));

        switch (decision) {
            case PROCEED_PASSIVE -> {
                s.markPassed(false);
                audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(), AuditEventType.PASSIVE_PASSED)
                        .field("median", fmt(median)));
                return FrameAssessment.passed(median);
            }
            case ESCALATE_ACTIVE -> {
                if (s.policy().activeLivenessEnabled()) {
                    s.markEscalated();
                    metrics.recordEscalation();
                    audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(),
                            AuditEventType.ESCALATED_TO_ACTIVE).field("median", fmt(median)));
                    return FrameAssessment.escalated(median);
                }
                s.fail(LivenessErrorCode.LIVENESS_SCORE_BELOW_THRESHOLD, SessionSummary.Outcome.FAILED_LIVENESS);
                metrics.recordSessionEnd(true, null, false);
                audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(), AuditEventType.LIVENESS_FAILED)
                        .field("median", fmt(median)).field("threshold", threshold));
                return FrameAssessment.failed(LivenessErrorCode.LIVENESS_SCORE_BELOW_THRESHOLD, median);
            }
            default -> throw new IllegalStateException("unexpected outcome");
        }
    }

    private FrameAssessment reject(LivenessSession s, LivenessErrorCode code) {
        audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(), AuditEventType.FRAME_REJECTED)
                .field("code", code.name()));
        return FrameAssessment.retryableError(code);
    }

    private FrameAssessment blockForPad(LivenessSession s, PadVerdict pad) {
        s.setPadAttack(pad.attackType());
        s.fail(LivenessErrorCode.PAD_FAILURE, SessionSummary.Outcome.PAD_BLOCKED);
        metrics.recordPadBlock();
        metrics.recordSessionEnd(true, null, false);
        audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(), AuditEventType.PAD_BLOCKED)
                .field("attackType", pad.attackType().name())
                .field("confidence", fmt(pad.confidence())));
        return FrameAssessment.padBlocked(pad);
    }

    // ------------------------------------------------------------------ challenges

    @Override
    public Challenge requestChallenge(String sessionId) {
        return requestChallenge(sessionId, Set.of());
    }

    /**
     * Issue the next engine-selected challenge with the given types excluded
     * from the draw pool (used by the Android orchestrator to drop BLINK when
     * the measured stream rate is too low to sample a blink reliably — the
     * engine stays fps-agnostic and the caller adapts the pool). Exclusion
     * never yields an empty pool: if every allowed type were excluded, the
     * unfiltered pool is used — a less-than-ideal challenge beats no
     * challenge at all.
     */
    public Challenge requestChallenge(String sessionId, Set<ChallengeType> excludedTypes) {
        LivenessSession s = requireSession(sessionId);
        assertNotTerminal(s);
        if (s.state() != LivenessSession.State.ESCALATED) {
            throw new LivenessException(LivenessErrorCode.INVALID_STATE,
                    "challenge requested outside escalation state: " + s.state());
        }
        Set<ChallengeType> pool = s.policy().allowedChallenges();
        if (excludedTypes != null && !excludedTypes.isEmpty()) {
            EnumSet<ChallengeType> filtered = EnumSet.noneOf(ChallengeType.class);
            filtered.addAll(pool);
            filtered.removeAll(excludedTypes);
            if (!filtered.isEmpty()) {
                pool = filtered;
            }
        }
        Challenge challenge = s.selector().next(pool, s.challengeAttempts());
        s.setCurrentChallenge(challenge);
        s.issueChallenge(clock.millis());
        audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(), AuditEventType.CHALLENGE_ISSUED)
                .field("type", challenge.type().name())
                .field("attempt", s.challengeAttempts())
                .field("excluded", excludedTypes == null || excludedTypes.isEmpty()
                        ? "none"
                        : String.join(",", excludedTypes.stream().map(Enum::name).sorted().toList()))
                .field("timeoutMs", challenge.timeoutMs()));
        return challenge;
    }

    @Override
    public ValidationResult validateChallenge(String sessionId, List<Frame> frames) {
        LivenessSession s = requireSession(sessionId);
        assertNotTerminal(s);
        if (s.state() != LivenessSession.State.IN_CHALLENGE) {
            throw new LivenessException(LivenessErrorCode.INVALID_STATE,
                    "no active challenge to validate (state=" + s.state() + ")");
        }
        if (frames == null || frames.isEmpty()) {
            throw new LivenessException(LivenessErrorCode.INVALID_FRAME_DATA, "challenge frame set is empty");
        }

        checkChallengeDeadline(s);
        if (s.isTerminal()) {
            return ValidationResult.hardFailure(s.errorCode());
        }
        if (s.state() != LivenessSession.State.IN_CHALLENGE) {
            int retriesLeft = retryBudgetLeft(s);
            return ValidationResult.timedOutRetry(retriesLeft);
        }

        List<FaceSignals> sequence = new ArrayList<>(frames.size());
        for (Frame f : frames) {
            FaceSignals sig = safely(() -> backend.analyzeFrame(f));
            if (sig.faceCount() == 0) {
                return failChallenge(s, "face_lost_during_challenge");
            }
            PadVerdict pad = safely(() -> backend.assessPad(f, sig));
            if (pad.attackDetected()) {
                blockForPad(s, pad);
                return ValidationResult.padBlocked();
            }
            // v3: passive re-evaluation in batch mode too
            double passiveScore = safely(() -> backend.scorePassiveLiveness(f, sig));
            s.addActivePassiveScore(passiveScore);
            if (s.passiveScoreFailedDuringActive(s.policy().passiveThresholdActive())) {
                s.fail(LivenessErrorCode.ACTIVE_REEVAL_FAILED, SessionSummary.Outcome.FAILED_LIVENESS);
                metrics.recordSessionEnd(true, null, false);
                return ValidationResult.hardFailure(LivenessErrorCode.ACTIVE_REEVAL_FAILED);
            }
            sequence.add(sig);
        }

        Challenge challenge = s.currentChallenge();
        long completionMs = clock.millis() - s.challengeStartedAtMillis();
        boolean passed = ChallengeEvaluators.evaluate(challenge, sequence);

        if (passed) {
            s.registerChallengeSuccess();
            metrics.recordChallengeCompletion(completionMs);
            audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(), AuditEventType.CHALLENGE_PASSED)
                    .field("type", challenge.type().name())
                    .field("attempt", s.challengeAttempts())
                    .field("completionMs", completionMs));
            int remaining = Math.max(0, s.policy().minChallengeCount() - s.challengesPassed());
            if (remaining == 0) {
                s.markPassed(true);
                audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(),
                        AuditEventType.PASSIVE_PASSED).field("viaActive", true));
                return ValidationResult.finalPass();
            }
            s.awaitNextChallenge();
            return ValidationResult.partialPass(remaining);
        }

        return failChallenge(s, "signal_not_matched");
    }

    private ValidationResult failChallenge(LivenessSession s, String reason) {
        s.registerChallengeFailure();
        metrics.recordRetry();
        boolean timedOut = reason.equals("challenge_timeout");
        audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(),
                        timedOut ? AuditEventType.CHALLENGE_TIMEOUT : AuditEventType.CHALLENGE_FAILED)
                .field("type", s.currentChallenge() == null ? "unknown" : s.currentChallenge().type().name())
                .field("attempt", s.challengeAttempts()));

        if (retryBudgetExhausted(s)) {
            applyRepeatedFailurePolicy(s);
            return ValidationResult.hardFailure(LivenessErrorCode.MAX_RETRIES_EXCEEDED);
        }
        s.awaitNextChallenge();
        int retriesLeft = retryBudgetLeft(s);
        return timedOut ? ValidationResult.timedOutRetry(retriesLeft)
                        : ValidationResult.retryAvailable(retriesLeft);
    }

    private void checkChallengeDeadline(LivenessSession s) {
        if (s.state() != LivenessSession.State.IN_CHALLENGE) return;
        if (clock.millis() <= s.challengeDeadlineMillis()) return;
        Challenge issued = s.currentChallenge();
        s.registerChallengeFailure();
        metrics.recordRetry();
        audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(), AuditEventType.CHALLENGE_TIMEOUT)
                .field("type", issued == null ? "unknown" : issued.type().name())
                .field("attempt", s.challengeAttempts()));
        if (retryBudgetExhausted(s)) {
            applyRepeatedFailurePolicy(s);
        } else {
            s.awaitNextChallenge();
        }
    }

    private void applyRepeatedFailurePolicy(LivenessSession s) {
        RepeatedFailureAction action = s.policy().onRepeatedFailure();
        SessionSummary.Outcome outcome = switch (action) {
            case LOCK_OUT -> SessionSummary.Outcome.FAILED_MAX_RETRIES_LOCKED_OUT;
            case FALLBACK -> SessionSummary.Outcome.FAILED_MAX_RETRIES_FALLBACK;
            case ESCALATE_TO_OPERATOR -> SessionSummary.Outcome.FAILED_MAX_RETRIES_OPERATOR_ESCALATION;
        };
        s.fail(LivenessErrorCode.MAX_RETRIES_EXCEEDED, outcome);
        metrics.recordSessionEnd(true, null, false);
        audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(),
                AuditEventType.MAX_RETRIES_EXCEEDED).field("attempts", s.challengeAttempts()));
        audit.log(AuditEvent.of(clock.millis(), s.sessionId(), s.workflow(),
                AuditEventType.REPEATED_FAILURE_ACTION).field("action", action.name()));
    }

    private boolean retryBudgetExhausted(LivenessSession s) {
        return s.challengeAttempts() >= 1 + s.policy().maxRetries();
    }

    private int retryBudgetLeft(LivenessSession s) {
        return Math.max(0, s.policy().maxRetries() - (s.challengeAttempts() - 1));
    }

    // ------------------------------------------------------------------ close

    @Override
    public SessionSummary closeSession(String sessionId) {
        LivenessSession s = sessions.remove(sessionId);
        if (s == null) {
            throw new LivenessException(LivenessErrorCode.INVALID_SESSION, "unknown session " + sessionId);
        }
        if (!s.isTerminal() && s.state() != LivenessSession.State.PASSED && !s.bypass()) {
            s.abort(clock.millis());
            audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(), AuditEventType.SESSION_ABORTED));
        }
        long duration = clock.millis() - s.startedAtMillis();
        SessionSummary summary = s.summarize(duration);
        metrics.recordSessionEnd(!summary.passed(), null, false); // Session end: not a specific PAD attack
        audit.log(AuditEvent.of(clock.millis(), sessionId, s.workflow(), AuditEventType.SESSION_CLOSED)
                .field("outcome", summary.outcome().name())
                .field("escalated", summary.escalatedToActive())
                .field("challengesIssued", summary.challengesIssued())
                .field("retriesUsed", summary.challengesFailed()));
        return summary;
    }

    // ------------------------------------------------------------------ helpers

    private LivenessSession requireSession(String sessionId) {
        LivenessSession s = sessions.get(sessionId);
        if (s == null) {
            throw new LivenessException(LivenessErrorCode.INVALID_SESSION, "unknown or closed session");
        }
        return s;
    }

    private void assertNotTerminal(LivenessSession s) {
        if (s.isTerminal()) {
            throw new LivenessException(LivenessErrorCode.INVALID_STATE,
                    "session already ended with outcome " + s.outcome());
        }
    }

    private interface BackendCall<T> { T call(); }

    private <T> T safely(BackendCall<T> call) {
        try {
            return call.call();
        } catch (LivenessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "backend failure: " + e.getMessage(), e);
        }
    }

    private static String fmt(double v) {
        return v == Math.floor(v) && !Double.isInfinite(v) ? String.valueOf((long) v)
                : String.valueOf(Math.round(v * 10000.0) / 10000.0);
    }

    /** Exposed for monitoring/health dashboards. */
    public MetricsCollector metrics() {
        return metrics;
    }
}
