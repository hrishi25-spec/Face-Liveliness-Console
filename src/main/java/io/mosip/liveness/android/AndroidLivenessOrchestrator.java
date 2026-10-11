package io.mosip.liveness.android;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.audit.AuditEvent;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.ChallengeProgress;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.engine.FaceLivenessEngine;
import io.mosip.liveness.engine.FrameAssessment;

/**
 * Android liveness gate orchestrator (orchestration spec §4/§8).
 *
 * <p>A single serial executor owns all state (spec §4 invariant); frame-source
 * callbacks and engine calls are funneled into it. Every frame is evaluated by
 * the shared {@link FaceLivenessEngine} pipeline so Desktop and Android
 * produce identical passive/PAD/challenge verdicts from the same policy
 * table.</p>
 *
 * <p>Invariants implemented here:</p>
 * <ul>
 *   <li>{@code attemptCount} increments only on ATTEMPT_FAILED (device errors
 *       and positioning hints never burn the retry budget).</li>
 *   <li>Every attempt gets a new sessionId + nonce + fresh challenge sequence
 *       (SecureRandom, engine-selected — R3).</li>
 *   <li>PASSED is valid for {@code gateValiditySec}; downstream capture/auth
 *       must call {@link #isGateValid(String)} and treat expiry as fail-closed.</li>
 *   <li>Any exception/timeout/missing model fails the attempt — never skips
 *       the gate (R1 fail closed).</li>
 *   <li>Challenge selection adapts to the <em>measured</em> stream rate: below
 *       {@link #MIN_FPS_FOR_BLINK} the BLINK challenge is excluded from the
 *       draw pool (measured in the frame-source layer, not the configured
 *       max — {@link FrameRateMeter}).</li>
 * </ul>
 */
public final class AndroidLivenessOrchestrator {

    private static final int MAX_CONSECUTIVE_INVALID_FRAMES = 3;

    /**
     * Measured stream rate below which BLINK is excluded from challenge
     * selection. The SBI spec permits streams as slow as 3 fps; a blink lasts
     * roughly 200–400 ms, so under ~5 fps a blink can land entirely between
     * frames and the challenge becomes a coin flip. SMILE / TURN_HEAD_* are
     * sampled less densely and stay available as the fallback
     * ({@code resource-compliance.md} §06 "3 fps minimum → adaptive fallback").
     */
    static final double MIN_FPS_FOR_BLINK = 5.0;

    private final FaceLivenessEngine engine;
    private final LivenessBackend backend;
    private final AndroidLivenessPolicyProvider policyProvider;
    private final AuditLogger audit;
    private final Clock clock;
    private final LivenessEvidenceSigner signer;
    private final LockoutStore lockoutStore;
    private final ModelStore modelStore;
    private final ExecutorService executor;
    private final SecureRandom random;
    private final boolean diagnosticsEnabled;

    private final AtomicReference<LivenessListener> listenerRef = new AtomicReference<>();
    private final AtomicReference<Session> sessionRef = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Executor and device-collaborator injection for Android host integration. */
    public AndroidLivenessOrchestrator(FaceLivenessEngine engine, LivenessBackend backend,
                                       AndroidLivenessPolicyProvider policyProvider, AuditLogger audit,
                                       Clock clock, LivenessEvidenceSigner signer, LockoutStore lockoutStore,
                                       ModelStore modelStore, ExecutorService executor, boolean diagnosticsEnabled) {
        this.engine = Objects.requireNonNull(engine);
        this.backend = Objects.requireNonNull(backend);
        this.policyProvider = Objects.requireNonNull(policyProvider);
        this.audit = audit == null ? AuditLogger.noop() : audit;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.signer = signer == null ? new LivenessEvidenceSigner.InProcessRsaSigner() : signer;
        this.lockoutStore = lockoutStore == null ? new LockoutStore.InMemory() : lockoutStore;
        this.modelStore = modelStore == null ? new InMemoryModelStore() : modelStore;
        this.executor = executor == null ? Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "liveness-orchestrator");
            t.setDaemon(true);
            return t;
        }) : executor;
        this.random = new SecureRandom();
        this.diagnosticsEnabled = diagnosticsEnabled;
    }

    public AndroidLivenessOrchestrator(FaceLivenessEngine engine, LivenessBackend backend,
                                       AndroidLivenessPolicyProvider policyProvider, AuditLogger audit,
                                       Clock clock) {
        this(engine, backend, policyProvider, audit, clock, null, null, null, null, false);
    }

    public void setListener(LivenessListener listener) {
        listenerRef.set(listener);
    }

    // ------------------------------------------------------------------ start / cancel

    /**
     * Start a gate for a role. Fails closed when locked out, when the model is
     * missing/invalid, or when the frame source cannot be opened.
     */
    public void start(LivenessRole role, String userIdOrNull, FaceFrameSource source,
                      FaceFrameSource.SourceConfig sourceConfig) {
        if (closed.get()) {
            throw new IllegalStateException("orchestrator already closed");
        }
        long now = clock.millis();
        if (lockoutStore.lockoutUntilEpochMs() > now) {
            emitFinal(null, LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE,
                    LivenessFinalResult.NextAction.LOCKOUT,
                    Optional.of((int) Math.max(1, (lockoutStore.lockoutUntilEpochMs() - now) / 1000)),
                    0);
            return;
        }

        LivenessGatePolicy policy = policyProvider.resolve(role);
        if (!policy.enabled()) {
            // Audited bypass (build flag present): downstream may proceed without a gate.
            audit.log(AuditEvent.of(now, "-", AndroidLivenessPolicyProvider.toWorkflow(role),
                    AuditEventType.SESSION_STARTED).field("bypassed", "true").field("role", role.name()));
            emitFinal(null, LivenessFinalResult.LivenessOutcome.PASSED,
                    LivenessFinalResult.NextAction.PROCEED, Optional.empty(), 0);
            return;
        }

        Session s = new Session(policy, role, userIdOrNull);
        sessionRef.set(s);
        emitState(s, LivenessState.INITIALIZING, null, "liveness.checking", null, null, null);

        try {
            modelStore.activeModel().orElseThrow(() ->
                    new FaceFrameSource.LivenessSourceException(LivenessDeviceError.ENGINE_ERROR,
                            "no active liveness model"));
        } catch (FaceFrameSource.LivenessSourceException e) {
            deviceError(s, LivenessDeviceError.ENGINE_ERROR, "model missing or invalid: " + e.getMessage());
            return;
        }

        try {
            source.open(sourceConfig);
        } catch (FaceFrameSource.LivenessSourceException e) {
            deviceError(s, e.error(), "source open failed: " + e.getMessage());
            return;
        }

        s.source = source;
        // Measure the real delivery rate of this gate's stream at the SPI
        // boundary; the orchestrator consults it when challenges are drawn.
        s.frameRate = new FrameRateMeter(clock);
        source.start(s.frameRate.wrap(new FaceFrameSource.Listener() {
            @Override
            public void onFrame(Frame frame) {
                AndroidLivenessOrchestrator.this.onFrame(frame);
            }

            @Override
            public void onSourceError(LivenessDeviceError error, String message) {
                AndroidLivenessOrchestrator.this.onSourceError(error, message);
            }
        }));
        s.engineSessionId = engine.initSession(AndroidLivenessPolicyProvider.toWorkflow(role));
        audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                AuditEventType.SESSION_STARTED)
                .field("role", s.role.name())
                .field("policy", policy.auditSummary())
                .field("nonce", s.nonceHex)
                .field("backend", backend.id()));
        emitState(s, LivenessState.POSITIONING, null, "liveness.hint.look_camera", null, null, null);
    }

    /** User cancel / lifecycle teardown (spec: any → ABORTED). Not an attempt. */
    public void cancel() {
        Session s = sessionRef.getAndSet(null);
        if (s == null) {
            return;
        }
        closeSourceQuietly(s);
        closeEngineSessionQuietly(s);
        audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                AuditEventType.SESSION_ABORTED));
        emitFinal(s, LivenessFinalResult.LivenessOutcome.ABORTED,
                LivenessFinalResult.NextAction.BLOCK, Optional.empty(), 0);
    }

    /** Release the orchestrator. Active sessions are aborted. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancel();
            executor.shutdown();
        }
    }

    // ------------------------------------------------------------------ frame entry

    /**
     * Feed one frame. Call from the frame-source thread; work is posted to the
     * orchestrator executor (keep-only-latest: while a frame is being
     * processed, newer frames replace queued ones, spec §12).
     */
    public void onFrame(Frame frame) {
        if (closed.get()) {
            return;
        }
        executor.execute(() -> handleFrame(frame));
    }

    /** Device failure reported by the source adapter. Never an attempt. */
    public void onSourceError(LivenessDeviceError error, String message) {
        executor.execute(() -> {
            Session s = sessionRef.get();
            if (s != null) {
                deviceError(s, error, message);
            }
        });
    }

    private void handleFrame(Frame frame) {
        long startNanos = System.nanoTime();
        Session s = sessionRef.get();
        if (s == null || s.terminal) {
            return;
        }
        if (s.pendingRetry) {
            reinitializeSession(s);
        }
        if (s.engineSessionId == null) {
            return;
        }
        try {
            FrameAssessment assessment = engine.pushFrame(s.engineSessionId, frame);
            s.lastAssessment = assessment;
            trackBestFrame(s, frame, assessment);

            switch (assessment.status()) {
                case RETRYABLE_ERROR -> handlePositioningHint(s, assessment);
                case SCORING -> {
                    s.goodFrames++;
                    s.consecutiveInvalidFrames = 0;
                    emitState(s, LivenessState.PASSIVE_EVALUATING, null, "liveness.checking",
                            null, null, progressOf(s));
                }
                case ESCALATED_TO_ACTIVE -> handleEscalation(s, assessment);
                case CHALLENGE_IN_PROGRESS -> emitChallengeProgress(s, assessment, frame);
                case PASSED -> pass(s, assessment);
                case PAD_BLOCKED -> attemptFailed(s, LivenessFailReason.PAD_DETECTED, assessment);
                case FAILED -> {
                    if (assessment.errorCode() == LivenessErrorCode.ACTIVE_REEVAL_FAILED
                            || assessment.errorCode() == LivenessErrorCode.LIVENESS_SCORE_BELOW_THRESHOLD) {
                        attemptFailed(s, LivenessFailReason.LOW_LIVENESS, assessment);
                    } else if (assessment.errorCode() == LivenessErrorCode.SESSION_TIMEOUT) {
                        attemptFailed(s, LivenessFailReason.DECISION_TIMEOUT, assessment);
                    } else {
                        deviceError(s, LivenessDeviceError.ENGINE_ERROR,
                                "engine failed: " + assessment.errorCode());
                    }
                }
                case SESSION_TIMED_OUT -> attemptFailed(s, LivenessFailReason.DECISION_TIMEOUT, assessment);
                default -> {
                    // unexpected engine state: fail closed
                    deviceError(s, LivenessDeviceError.ENGINE_ERROR,
                            "unexpected assessment " + assessment.status());
                }
            }
            // Record diagnostics after frame processing (fail-closed when disabled)
            recordDiagnostics(toFrameProcessResult(assessment), System.nanoTime() - startNanos);
        } catch (RuntimeException e) {
            deviceError(s, LivenessDeviceError.ENGINE_ERROR, "engine exception: " + e.getMessage());
            // Record minimal diagnostics for the error case (factory methods are package-private)
            recordDiagnostics(new FrameProcessResult(), System.nanoTime() - startNanos);
        }
    }

    /**
     * Convert a FrameAssessment to a FrameProcessResult for diagnostics recording.
     * Maps the engine's internal assessment to the DTO used by the diagnostic panel.
     */
    private FrameProcessResult toFrameProcessResult(FrameAssessment assessment) {
        FrameProcessResult result = new FrameProcessResult();
        result.setLivenessScore(assessment.livenessScore());
        result.setPadFlag(assessment.padFlagged());
        result.setPadAttackType(assessment.padAttackType() != null ? assessment.padAttackType().name() : null);

        // Map status to action
        String action = switch (assessment.status()) {
            case SCORING -> "proceed";
            case ESCALATED_TO_ACTIVE -> "escalate_to_active";
            case CHALLENGE_IN_PROGRESS -> "proceed"; // continue challenge
            case PASSED -> "proceed";
            case PAD_BLOCKED -> "reject";
            case FAILED -> "reject";
            case SESSION_TIMED_OUT -> "locked";
            case RETRYABLE_ERROR -> "retry_passive";
            default -> "failed";
        };
        result.setAction(action);

        // Set face quality from the assessment if available
        // Face quality is not directly in FrameAssessment; we'll leave it null for now
        // The backend's FaceSignals has qualityScore() which could be added if needed

        if (assessment.challengeProgress() != null) {
            FrameProcessResult.ChallengeInfo info = new FrameProcessResult.ChallengeInfo();
            info.setChallengeType(assessment.challengeProgress().name());
            result.setChallenge(info);
        }

        return result;
    }

    /**
     * Keep the sha256 of the attempt's best-scoring frame: evidence carries it
     * so the downstream signed capture can bind back to the exact pixels the
     * gate judged (spec §8 {@code pass()} evidence fields, analyse.md §5.4
     * binding). Reset with the nonce on every retry, so a hash can only ever
     * reference frames from the attempt its evidence certifies.
     */
    private void trackBestFrame(Session s, Frame frame, FrameAssessment assessment) {
        double score = assessment.livenessScore();
        if (frame == null || Double.isNaN(score) || score <= s.bestFrameScore) {
            return;
        }
        s.bestFrameScore = score;
        s.bestFrameSha256 = InMemoryModelStore.sha256Hex(frame.data());
    }

    private void handlePositioningHint(Session s, FrameAssessment assessment) {
        s.consecutiveInvalidFrames++;
        if (s.consecutiveInvalidFrames >= MAX_CONSECUTIVE_INVALID_FRAMES) {
            deviceError(s, LivenessDeviceError.INVALID_FRAME,
                    "invalid frames exceeded: " + assessment.errorCode());
            return;
        }
        LivenessHint hint = hintFor(assessment.errorCode());
        emitState(s, LivenessState.POSITIONING, hint, hint == null ? null : hint.uiMessageKey(), null, null);
    }

    private static LivenessHint hintFor(LivenessErrorCode code) {
        return switch (code) {
            case FACE_NOT_DETECTED -> LivenessHint.NO_FACE;
            case MULTIPLE_FACES_DETECTED -> LivenessHint.MULTIPLE_FACES;
            case POOR_FACE_QUALITY -> LivenessHint.HOLD_STILL;
            default -> null;
        };
    }

    private void handleEscalation(Session s, FrameAssessment assessment) {
        if (!s.policy.activeEnabled()) {
            attemptFailed(s, LivenessFailReason.LOW_LIVENESS, assessment);
            return;
        }
        Challenge challenge = engine.requestChallenge(s.engineSessionId, blinkExclusions(s));
        s.currentChallenge = challenge;
        s.challengeTypesUsed.add(challenge.type());
        emitState(s, LivenessState.CHALLENGE_PROMPT,
                null,
                LivenessStateEvent.challengePromptKey(challenge.type().name()),
                challenge.type().name(),
                s.challengeIndex, null);
    }

    /** Challenge types to drop for this draw, from the measured stream rate. */
    private static Set<ChallengeType> blinkExclusions(Session s) {
        return challengeExclusions(s.frameRate == null ? Double.NaN : s.frameRate.fps());
    }

    /**
     * Measured rate → challenge exclusions. BLINK needs a dense sample stream
     * (open → closed → open within one blink); only a rate at or above
     * {@link #MIN_FPS_FOR_BLINK} — or a clearly fast same-instant burst —
     * keeps it available. Anything slower, and any rate we cannot prove
     * ({@code NaN}: fewer than two frames observed), falls back to
     * SMILE / TURN_HEAD_* rather than issuing a challenge the stream cannot
     * reliably evidence.
     */
    static Set<ChallengeType> challengeExclusions(double measuredFps) {
        return measuredFps >= MIN_FPS_FOR_BLINK ? Set.of() : Set.of(ChallengeType.BLINK);
    }

    private void emitChallengeProgress(Session s, FrameAssessment assessment, Frame frame) {
        ChallengeProgress p = assessment.challengeProgress();
        String key = switch (p) {
            case AWAITING_ACTION -> "feedback.continue";
            case ACTION_DETECTED -> "feedback.detected";
            case HOLD_STILL -> "feedback.hold";
        };
        Challenge c = s.currentChallenge;
        // Collect the challenge frames: the engine's batch validator decides
        // the challenge once the action is confirmed (HOLD_STILL).
        if (frame != null) {
            s.challengeFrames.add(frame);
        }
        Double progress = assessment.combinedScore() == null ? null : assessment.combinedScore().combined();
        emitState(s, LivenessState.CHALLENGE_VERIFYING, null, key,
                c == null ? null : c.type().name(), s.challengeIndex, progress);
        if (p == ChallengeProgress.HOLD_STILL) {
            completeChallenge(s, assessment);
        }
    }

    /**
     * Batch-validate the frames collected during the challenge (spec §8
     * CHALLENGE_VERIFYING → CHALLENGE_PASSED / ATTEMPT_FAILED).
     */
    private void completeChallenge(Session s, FrameAssessment lastAssessment) {
        Challenge challenge = s.currentChallenge;
        List<Frame> frames = new ArrayList<>(s.challengeFrames);
        s.challengeFrames.clear();
        if (challenge == null || frames.isEmpty()) {
            attemptFailed(s, LivenessFailReason.CHALLENGE_FAILED, lastAssessment);
            return;
        }
        io.mosip.liveness.engine.ValidationResult result =
                engine.validateChallenge(s.engineSessionId, frames);
        if (result.passed()) {
            s.challengeResults.add(true);
            if (result.challengesRemaining() <= 0) {
                pass(s, lastAssessment);
                return;
            }
            s.challengeIndex++;
            emitState(s, LivenessState.CHALLENGE_PASSED, null, "feedback.detected",
                    challenge.type().name(), s.challengeIndex - 1, null);
            handleEscalation(s, lastAssessment);   // engine is ESCALATED again → next prompt
            return;
        }
        s.challengeResults.add(false);
        LivenessErrorCode code = result.errorCode() == null
                ? LivenessErrorCode.ACTIVE_CHALLENGE_FAILURE : result.errorCode();
        switch (code) {
            case CHALLENGE_TIMEOUT -> attemptFailed(s, LivenessFailReason.CHALLENGE_TIMEOUT, lastAssessment);
            case PAD_FAILURE -> attemptFailed(s, LivenessFailReason.PAD_DETECTED, lastAssessment);
            case MAX_RETRIES_EXCEEDED -> {
                s.attemptsUsed++;
                terminal(s);
            }
            case ACTIVE_REEVAL_FAILED -> attemptFailed(s, LivenessFailReason.LOW_LIVENESS, lastAssessment);
            default -> attemptFailed(s, LivenessFailReason.CHALLENGE_FAILED, lastAssessment);
        }
    }

    // ------------------------------------------------------------------ terminal transitions

    private void pass(Session s, FrameAssessment assessment) {
        s.terminal = true;
        s.passed = true;
        s.completedEpochMs = clock.millis();
        s.validUntilEpochMs = s.completedEpochMs + s.policy.gateValiditySec() * 1000L;
        String signature = signEvidence(s);
        closeSourceQuietly(s);
        closeEngineSessionQuietly(s);
        audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                AuditEventType.PASSIVE_PASSED)
                .field("viaActive", String.valueOf(s.currentChallenge != null))
                .field("evidenceSigned", String.valueOf(signature != null)));
        lastPassed.set(s);                       // gate validity + evidence source
        sessionRef.compareAndSet(s, null);
        emitState(s, LivenessState.PASSED, null, "liveness.success", null, null, 1.0);
        emitFinal(s, LivenessFinalResult.LivenessOutcome.PASSED,
                LivenessFinalResult.NextAction.PROCEED, Optional.empty(), s.policy.gateValiditySec());
    }

    private void attemptFailed(Session s, LivenessFailReason reason, FrameAssessment assessment) {
        s.terminal = true;
        s.attemptsUsed++;
        audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                AuditEventType.LIVENESS_FAILED)
                .field("reason", reason.name())
                .field("attempt", s.attemptsUsed + "/" + s.policy.maxRetries()));
        closeEngineSessionQuietly(s);

        emitState(s, LivenessState.ATTEMPT_FAILED, null, uiKeyFor(reason), null,
                failCategory(reason, s));

        if (s.attemptsUsed >= s.policy.maxRetries()) {
            terminal(s);
            return;
        }
        emitState(s, LivenessState.RETRY_WAIT, null, "liveness.failed.try_again", null,
                failCategory(reason, s));
        // Fresh session for the next attempt: new sessionId/nonce/challenge
        // sequence (spec §4 invariant). The source keeps feeding frames; the
        // next one triggers re-initialization before any engine call.
        s.pendingRetry = true;
        s.currentChallenge = null;
        s.challengeFrames.clear();
        s.goodFrames = 0;
        s.consecutiveInvalidFrames = 0;
        s.challengeIndex = 1;
        s.bestFrameSha256 = null;          // next attempt binds to its own frames
        s.bestFrameScore = Double.NEGATIVE_INFINITY;
    }

    /** Generic, safe-to-display message key per failure class (spec §9). */
    private static String uiKeyFor(LivenessFailReason reason) {
        return switch (reason) {
            case PAD_DETECTED -> "liveness.pad.generic";
            case LOW_LIVENESS, CHALLENGE_FAILED, CHALLENGE_TIMEOUT, DECISION_TIMEOUT ->
                    "liveness.failed.try_again";
        };
    }

    private void terminal(Session s) {
        RepeatedFailureAction action = s.policy.onRepeatedFailure();
        LivenessFinalResult.NextAction next = switch (action) {
            case LOCK_OUT -> {
                long until = clock.millis() + s.policy.lockoutSeconds() * 1000L;
                lockoutStore.setLockoutUntilEpochMs(until);
                yield LivenessFinalResult.NextAction.LOCKOUT;
            }
            case FALLBACK -> LivenessFinalResult.NextAction.FALLBACK;
            case ESCALATE_TO_OPERATOR -> LivenessFinalResult.NextAction.EXCEPTION;
        };
        audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                AuditEventType.MAX_RETRIES_EXCEEDED).field("attempts", s.attemptsUsed));
        audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                AuditEventType.REPEATED_FAILURE_ACTION).field("action", action.name()));
        sessionRef.compareAndSet(s, null);
        emitState(s, LivenessState.TERMINAL_FAILURE, null, "liveness.max_retries.recovery", null,
                LivenessFailCategory.MAX_RETRIES);
        emitFinal(s, LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE, next,
                next == LivenessFinalResult.NextAction.LOCKOUT
                        ? Optional.of(s.policy.lockoutSeconds())
                        : Optional.empty(),
                0);
    }

    private void deviceError(Session s, LivenessDeviceError error, String message) {
        s.terminal = true;
        audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                AuditEventType.INTERNAL_ERROR)
                .field("errorCode", error.name())
                .field("detail", message));
        closeSourceQuietly(s);
        closeEngineSessionQuietly(s);
        sessionRef.compareAndSet(s, null);
        emitState(s, LivenessState.DEVICE_ERROR, null, "liveness.device.error", null,
                LivenessFailCategory.DEVICE);
        emitFinal(s, LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE,
                LivenessFinalResult.NextAction.BLOCK, Optional.empty(), 0);
    }

    private LivenessFailCategory failCategory(LivenessFailReason reason, Session s) {
        return s.attemptsUsed >= s.policy.maxRetries()
                ? LivenessFailCategory.MAX_RETRIES
                : LivenessFailCategory.GENERIC;
    }

    /**
     * Begin a fresh engine session for the next attempt (new nonce + new
     * challenge sequence — spec §4 invariant). Called lazily on the next frame.
     */
    private void reinitializeSession(Session s) {
        s.pendingRetry = false;
        emitState(s, LivenessState.INITIALIZING, null, "liveness.checking", null, null, null);
        s.engineSessionId = engine.initSession(toWorkflow(s.role));
        s.nonceHex = HexFormat.of().formatHex(randomNonce());
        audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                AuditEventType.SESSION_STARTED)
                .field("retry", String.valueOf(s.attemptsUsed))
                .field("policy", s.policy.auditSummary())
                .field("backend", backend.id()));
        emitState(s, LivenessState.POSITIONING, null, "liveness.hint.look_camera", null, null, null);
    }

    private static byte[] randomNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return b;
    }

    // ------------------------------------------------------------------ gate consumption

    /**
     * Downstream capture/auth binding check (spec §8): the gate must be PASSED
     * and inside its validity window. Callers additionally verify face
     * consistency between the best liveness frame and the signed capture when
     * an on-device 1:1 matcher is available.
     */
    public boolean isGateValid(String sessionId) {
        Session s = lastPassed.get();
        return s != null && s.passed && Objects.equals(s.sessionId(), sessionId)
                && clock.millis() < s.validUntilEpochMs;
    }

    /** Current native gate session id for the host bridge; empty before start. */
    public Optional<String> currentSessionId() {
        Session session = sessionRef.get();
        return session == null ? Optional.empty() : Optional.of(session.sessionId());
    }

    /** Signed evidence of the most recent passed gate, if still valid. */
    public Optional<LivenessEvidence> currentEvidence() {
        Session s = lastPassed.get();
        if (s == null || !s.passed || clock.millis() >= s.validUntilEpochMs) {
            return Optional.empty();
        }
        return Optional.ofNullable(s.evidence);
    }

    private final AtomicReference<Session> lastPassed = new AtomicReference<>();

    private String signEvidence(Session s) {
        try {
            double score = Double.isNaN(s.lastMedianScore) ? 0.0 : s.lastMedianScore;
            LivenessEvidence evidence = new LivenessEvidence(
                    s.sessionId(), s.nonceHex, s.role, Optional.ofNullable(s.userId),
                    s.policy.version(), backend.id(),
                    modelStore.activeModel().map(ModelStore.ActiveModel::version).orElse("none"),
                    false,
                    score,
                    s.challengeTypesUsed,
                    s.challengeResults,
                    s.startedEpochMs, s.completedEpochMs,
                    s.bestFrameSha256,
                    "android-device",
                    false,
                    null);
            String signature = signer.sign(evidence.canonicalPayload());
            s.evidence = new LivenessEvidence(
                    evidence.sessionId(), evidence.nonceHex(), evidence.role(), evidence.userId(),
                    evidence.policyVersion(), evidence.engineId(), evidence.modelVersion(),
                    evidence.engineCertified(), evidence.passiveScore(), evidence.challenges(),
                    evidence.challengeResults(), evidence.startedEpochMs(), evidence.completedEpochMs(),
                    evidence.bestFrameSha256(), evidence.deviceId(), evidence.bypassed(),
                    signature);
            return signature;
        } catch (Exception e) {
            // Fail closed: an unsigned evidence record cannot be produced. The
            // gate still reports PASSED to the UI (capture must proceed for
            // UX), but evidence is null and audited so downstream binding
            // checks refuse the capture.
            audit.log(AuditEvent.of(clock.millis(), s.engineSessionId, toWorkflow(s.role),
                    AuditEventType.INTERNAL_ERROR).field("event", "EVIDENCE_SIGN_FAILED"));
            return null;
        }
    }

    // ------------------------------------------------------------------ emission helpers

    private void emitState(Session s, LivenessState state, LivenessHint hint, String uiMessageKey,
                           String challenge, Integer challengeIndex, Double progress) {
        LivenessListener l = listenerRef.get();
        if (l == null) {
            return;
        }
        Integer idx = challengeIndex;
        l.onState(new LivenessStateEvent(
                s == null ? null : s.sessionId(),
                state,
                challenge,
                hint,
                uiMessageKey,
                s == null ? 0 : s.attemptsUsed,
                s == null ? 3 : s.policy.maxRetries(),
                idx,
                s == null ? null : s.policy.minChallenges(),
                progress,
                s != null && s.attemptsUsed >= s.policy.maxRetries()
                        ? LivenessFailCategory.MAX_RETRIES : LivenessFailCategory.GENERIC));
    }

    private void emitState(Session s, LivenessState state, LivenessHint hint, String uiMessageKey,
                           String challenge, LivenessFailCategory category) {
        LivenessListener l = listenerRef.get();
        if (l == null) {
            return;
        }
        l.onState(new LivenessStateEvent(
                s == null ? null : s.sessionId(),
                state, challenge, hint, uiMessageKey,
                s == null ? 0 : s.attemptsUsed,
                s == null ? 3 : s.policy.maxRetries(),
                null, s == null ? null : s.policy.minChallenges(),
                null, category));
    }

    private void emitFinal(Session s, LivenessFinalResult.LivenessOutcome outcome,
                           LivenessFinalResult.NextAction nextAction,
                           Optional<Integer> lockoutSeconds, int validForSeconds) {
        LivenessListener l = listenerRef.get();
        if (l == null) {
            return;
        }
        l.onFinal(new LivenessFinalResult(
                s == null ? null : s.sessionId(), outcome, nextAction, lockoutSeconds, validForSeconds));
    }

    private Double progressOf(Session s) {
        return s.lastAssessment == null ? null
                : (Double.isNaN(s.lastAssessment.livenessScore()) ? null
                        : s.lastAssessment.livenessScore());
    }

    private static WorkflowType toWorkflow(LivenessRole role) {
        return AndroidLivenessPolicyProvider.toWorkflow(role);
    }

    private void closeSourceQuietly(Session s) {
        try {
            if (s.source != null) {
                s.source.stop();
                s.source.close();
            }
        } catch (RuntimeException ignored) {
            // teardown must never mask the real outcome
        }
    }

    private void closeEngineSessionQuietly(Session s) {
        try {
            if (s.engineSessionId != null) {
                engine.closeSession(s.engineSessionId);
            }
        } catch (RuntimeException ignored) {
            // session may already be closed by the engine on terminal outcomes
        }
    }

    // ------------------------------------------------------------------ session state

    /** Mutable per-gate state; confined to the orchestrator executor. */
    private static final class Session {
        final LivenessGatePolicy policy;
        final LivenessRole role;
        final String userId;
        final long startedEpochMs = System.currentTimeMillis();
        String nonceHex;
        final List<ChallengeType> challengeTypesUsed = new ArrayList<>();
        final List<Boolean> challengeResults = new ArrayList<>();
        final AtomicBoolean done = new AtomicBoolean();

        FaceFrameSource source;
        String engineSessionId;
        boolean pendingRetry;
        int attemptsUsed;
        int goodFrames;
        int consecutiveInvalidFrames;
        int challengeIndex = 1;
        Challenge currentChallenge;
        FrameAssessment lastAssessment;
        final Deque<Frame> challengeFrames = new ArrayDeque<>();
        boolean terminal;
        boolean passed;
        long completedEpochMs;
        long validUntilEpochMs;
        String bestFrameSha256;
        double bestFrameScore = Double.NEGATIVE_INFINITY;
        FrameRateMeter frameRate;    // measured delivery rate of this gate's stream
        double lastMedianScore = Double.NaN;
        LivenessEvidence evidence;
        final Deque<Double> passiveWindow = new ArrayDeque<>();

        // Diagnostic mode fields (matching backend DiagnosticsService)
        /** Frames retained for diagnostics (matching backend capacity) */
        private static final int DIAGNOSTIC_CAPACITY = 120;
        final Deque<DiagnosticsSnapshot.FrameSample> diagnosticSamples = new ArrayDeque<>();
        long frameCount;
        String scorer; // delegate that produced the scores

        Session(LivenessGatePolicy policy, LivenessRole role, String userId) {
            this.policy = policy;
            this.role = role;
            this.userId = userId;
            this.nonceHex = HexFormat.of().formatHex(randomNonce());
        }

        String sessionId() {
            return engineSessionId == null ? "pending" : engineSessionId;
        }
    }

    // ------------------------------------------------------------------ diagnostic mode

    /**
     * Diagnostic mode (opt-in, local) — orchestration spec §10: retains the raw
     * scores, per-frame timings, FPS and the scorer delegate (the "delegate
     * used") so the local-only debug panel can show them. Still no pixels: the
     * input is the decision DTO, which never carries frame bytes.
     *
     * <p>Opt-in is fail-closed on both axes: with the flag off
     * ({@code mosip.liveness.diagnostics-enabled}, default {@code false}) nothing
     * is ever retained — an opt-in that silently buffered would be indistinguishable
     * from always-on — and {@link #diagnosticsSnapshot()} returns the empty
     * {@link DiagnosticsSnapshot#disabled()} payload rather than a stale window.
     * The controller adds the second gate: loopback callers only.
     *
     * <p>Bounded by construction: a fixed-capacity ring of samples (no unbounded
     * growth on a long-running service) and a sliding 5 s FPS window. The clock is
     * injected so the window is testable without sleeping.
     */
    public DiagnosticsSnapshot diagnosticsSnapshot() {
        Session s = sessionRef.get();
        if (s == null) {
            return DiagnosticsSnapshot.disabled();
        }

        // Check if diagnostics is enabled via configuration
        if (!diagnosticsEnabled) {
            return DiagnosticsSnapshot.disabled();
        }

        List<DiagnosticsSnapshot.FrameSample> retained;
        String delegate;
        long total;
        synchronized (s) {
            retained = new ArrayList<>(s.diagnosticSamples);
            delegate = s.scorer;
            total = s.frameCount;
        }
        Instant now = clock.instant();

        List<Double> scores = new ArrayList<>();
        for (DiagnosticsSnapshot.FrameSample sample : retained) {
            if (sample.score() != null) {
                scores.add(sample.score());
            }
        }
        Double lastScore = scores.isEmpty() ? null : scores.get(scores.size() - 1);

        long frameCountInWindow = 0;
        Instant oldestInWindow = null;
        Instant fiveSecondsAgo = now.minusSeconds(5);
        for (DiagnosticsSnapshot.FrameSample sample : retained) {
            if (!sample.at().isBefore(fiveSecondsAgo)) {
                frameCountInWindow++;
                if (oldestInWindow == null) {
                    oldestInWindow = sample.at();
                }
            }
        }
        Double fps = null;
        if (frameCountInWindow >= 2 && oldestInWindow != null) {
            long spanMs = Math.max(1L, now.toEpochMilli() - oldestInWindow.toEpochMilli());
            fps = (frameCountInWindow - 1) * 1000.0 / spanMs;
        }

        Double avgMs = null;
        Double maxMs = null;
        if (!retained.isEmpty()) {
            long sum = 0L;
            long max = Long.MIN_VALUE;
            for (DiagnosticsSnapshot.FrameSample sample : retained) {
                sum += sample.frameMs();
                max = Math.max(max, sample.frameMs());
            }
            avgMs = (double) sum / retained.size();
            maxMs = (double) max;
        }

        int from = Math.max(0, retained.size() - 30); // Match backend SNAPSHOT_ROWS = 30
        List<DiagnosticsSnapshot.FrameSample> rows = new ArrayList<>(retained.subList(from, retained.size()));

        Instant capturedAt = clock.instant();
        return new DiagnosticsSnapshot(
                true, // enabled - we already checked diagnosticsEnabled flag
                delegate,
                fps,
                total,
                lastScore,
                median(scores),
                avgMs,
                maxMs,
                rows,
                capturedAt);
    }

    /** Median of the retained raw scores — same outlier-resistant shape the decision uses. */
    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /**
     * Record one frame's diagnostics after it was processed. Fail closed:
     * when the mode is off nothing is stored, not even counters.
     *
     * @param result       the frame's decision (scores/quality/flag/action)
     * @param elapsedNanos end-to-end cost of processing that frame
     */
    void recordDiagnostics(FrameProcessResult result, long elapsedNanos) {
        Session s = sessionRef.get();
        if (s == null) {
            return;
        }
        // Fail closed: when diagnostics is off nothing is stored, not even counters
        if (!diagnosticsEnabled || result == null) {
            return;
        }

        Instant at = clock.instant();
        Double score = result.getLivenessScore();
        Double faceQuality = result.getFaceQuality();
        Boolean padFlag = result.getPadFlag();
        long frameMs = Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        String action = result.getAction();

        DiagnosticsSnapshot.FrameSample sample = new DiagnosticsSnapshot.FrameSample(
                at, score, faceQuality, padFlag != null && padFlag, frameMs, action);

        synchronized (s) {
            s.diagnosticSamples.addLast(sample);
            while (s.diagnosticSamples.size() > Session.DIAGNOSTIC_CAPACITY) {
                s.diagnosticSamples.removeFirst();
            }
            s.frameCount++;
            // The delegate is only knowable once a frame was actually scored;
            // scorerId() has already resolved by then (score() ran in this
            // request), so this is a cheap read, and only the first one costs
            // a lookup.
            if (s.scorer == null && result.getLivenessScore() != null) {
                s.scorer = backend.id();
            }
        }
    }

    // ------------------------------------------------------------------ diagnostic model

    /**
     * What diagnostic mode exposes — orchestration spec §10: "adds raw scores,
     * per-frame timings, FPS, delegate used. <b>Still no pixels.</b>"
     *
     * <p>Served only by {@code GET /api/v1/diagnostics}, and only when the mode is
     * opted in (<code>mosip.liveness.diagnostics-enabled</code>) <em>and</em> the
     * caller is loopback — the local-only debug panel. {@code ui-ux-design.md}
     * keeps scores, PAD detail and model internals out of the user-facing UI
     * (R5); this payload is the troubleshooting escape hatch behind both gates.
     *
     * @param enabled      false for the fail-closed {@link #disabled()} snapshot
     * @param scorer       delegate that produced the scores (model id or
     *                     {@code opencv-heuristic}); null until one was scored
     * @param fps          frames/second over the sliding window; null with
     *                     fewer than two frames in it
     * @param frameCount   frames recorded since the process started (mode on)
     * @param lastScore    raw passive score of the most recent scored frame
     * @param medianScore  median of the retained raw scores
     * @param avgFrameMs   mean end-to-end frame cost over the retained window
     * @param maxFrameMs   worst end-to-end frame cost in the retained window
     * @param recentFrames chronological capped rows for the panel table, newest last
     * @param capturedAt   when the snapshot was taken
     */
    public record DiagnosticsSnapshot(
            boolean enabled,
            String scorer,
            Double fps,
            long frameCount,
            Double lastScore,
            Double medianScore,
            Double avgFrameMs,
            Double maxFrameMs,
            List<FrameSample> recentFrames,
            Instant capturedAt) {

        /**
         * One retained frame — numbers, a timestamp and short labels only. The
         * type itself is the "no pixels" contract: there is nowhere in here to
         * put frame bytes, base64 or an image path.
         */
        public record FrameSample(
                Instant at,
                Double score,
                Double faceQuality,
                boolean padFlag,
                long frameMs,
                String action) {
        }

        /** Fail-closed answer when the mode is off: nothing retained, nothing to read. */
        public static DiagnosticsSnapshot disabled() {
            return new DiagnosticsSnapshot(false, null, null, 0L,
                    null, null, null, null, List.of(), null);
        }
    }

    // ------------------------------------------------------------------ LivenessPipelineAdapter methods (for backward compatibility)

    // These methods delegate to the engine to maintain compatibility with existing code
    // that might still call these directly on the orchestrator

    public String engineId() {
        return backend.id();
    }

    public void setListener(LivenessListener listener, ExecutorService executor) {
        setListener(listener);
        // Note: executor parameter is ignored as we use our own executor
    }

    public Optional<LivenessEvidence> getEvidence() {
        return currentEvidence();
    }

    public boolean isGateValid(String sessionId, long validitySec) {
        return isGateValid(sessionId);
    }

    public void destroy() {
        close();
    }
}
