package io.mosip.liveness.engine;

import io.mosip.liveness.challenge.ChallengeSelector;
import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.ChallengeProgress;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.WorkflowType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Mutable state for one liveness session. Logic lives in {@link FaceLivenessEngine}. */
final class LivenessSession {

    enum State { SCORING, ESCALATED, IN_CHALLENGE, PASSED, FAILED }

    private final String sessionId;
    private final WorkflowType workflow;
    private final EffectivePolicy policy;
    private final long startedAtMillis;
    private final ChallengeSelector selector;
    private final ArrayDeque<Double> scores = new ArrayDeque<>();
    // v3: passive re-evaluation during active phase
    private final ArrayDeque<Double> activePassiveScores = new ArrayDeque<>();
    private final List<ChallengeProgress> challengeProgressEvents = new ArrayList<>();

    private State state = State.SCORING;
    private boolean bypass;
    private boolean escalated;
    private double lastMedianScore = Double.NaN;
    private Challenge currentChallenge;
    private long challengeStartedAtMillis;
    private long challengeDeadlineMillis;
    private int challengeAttempts;
    private int challengesPassed;
    private int challengesFailed;
    private int retriesUsed;
    private LivenessErrorCode errorCode;
    private SessionSummary.Outcome outcome;
    private io.mosip.liveness.core.PadAttackType padAttackType;
    // v3: track if action was detected for hold-still logic
    private boolean actionDetectedThisChallenge;
    // v7: per-session frame counter for sampling
    private int frameCounter;

    LivenessSession(String sessionId, WorkflowType workflow, EffectivePolicy policy, long startedAtMillis) {
        this.sessionId = sessionId;
        this.workflow = workflow;
        this.policy = policy;
        this.startedAtMillis = startedAtMillis;
        this.selector = new ChallengeSelector(policy.challengeTimeoutMs());
    }

    // ---- accessors ----
    String sessionId() { return sessionId; }
    WorkflowType workflow() { return workflow; }
    EffectivePolicy policy() { return policy; }
    State state() { return state; }
    boolean bypass() { return bypass; }
    boolean escalated() { return escalated; }
    boolean isTerminal() { return state == State.FAILED || state == State.PASSED; }
    Challenge currentChallenge() { return currentChallenge; }
    long challengeStartedAtMillis() { return challengeStartedAtMillis; }
    long challengeDeadlineMillis() { return challengeDeadlineMillis; }
    int challengeAttempts() { return challengeAttempts; }
    int challengesPassed() { return challengesPassed; }
    int challengesFailed() { return challengesFailed; }
    int retriesUsed() { return retriesUsed; }
    double lastMedianScore() { return lastMedianScore; }
    LivenessErrorCode errorCode() { return errorCode; }
    SessionSummary.Outcome outcome() { return outcome; }
    io.mosip.liveness.core.PadAttackType padAttackType() { return padAttackType; }
    ChallengeSelector selector() { return selector; }
    List<ChallengeProgress> challengeProgressEvents() { return List.copyOf(challengeProgressEvents); }
    boolean actionDetectedThisChallenge() { return actionDetectedThisChallenge; }

    // ---- session timeout check (G3) ----
    boolean isSessionTimedOut(long nowMillis) {
        return (nowMillis - startedAtMillis) > policy.maxSessionDurationMs();
    }

    // ---- transitions ----

    void markBypassed() {
        bypass = true;
        state = State.PASSED;
        outcome = SessionSummary.Outcome.BYPASSED;
    }

    void addScore(double score) {
        scores.addLast(score);
        while (scores.size() > policy.passiveWindowFrames()) {
            scores.pollFirst();
        }
    }

    boolean enoughPassiveFrames() {
        return scores.size() >= policy.passiveMinFrames();
    }

    double medianScore() {
        List<Double> snapshot = new ArrayList<>(scores);
        lastMedianScore = LivenessDecisionLogic.median(snapshot);
        return lastMedianScore;
    }

    void markPassed(boolean viaActive) {
        state = State.PASSED;
        outcome = viaActive ? SessionSummary.Outcome.PASSED_ACTIVE : SessionSummary.Outcome.PASSED_PASSIVE;
    }

    void markEscalated() {
        escalated = true;
        state = State.ESCALATED;
    }

    void issueChallenge(long nowMillis) {
        challengeAttempts++;
        challengeStartedAtMillis = nowMillis;
        challengeDeadlineMillis = nowMillis + policy.challengeTimeoutMs();
        state = State.IN_CHALLENGE;
        actionDetectedThisChallenge = false;
        activePassiveScores.clear();
    }

    void setCurrentChallenge(Challenge challenge) {
        this.currentChallenge = challenge;
    }

    void awaitNextChallenge() {
        currentChallenge = null;
        state = State.ESCALATED;
        actionDetectedThisChallenge = false;
    }

    void registerChallengeSuccess() {
        challengesPassed++;
    }

    void registerChallengeFailure() {
        challengesFailed++;
        retriesUsed++;
    }

    void fail(LivenessErrorCode code, SessionSummary.Outcome oc) {
        state = State.FAILED;
        errorCode = code;
        outcome = oc;
    }

    void setPadAttack(io.mosip.liveness.core.PadAttackType t) {
        this.padAttackType = t;
    }

    void abort(long nowMillis) {
        state = State.FAILED;
        outcome = SessionSummary.Outcome.ABORTED;
    }

    // v3: passive re-evaluation during active phase
    void addActivePassiveScore(double score) {
        activePassiveScores.addLast(score);
        while (activePassiveScores.size() > policy.passiveWindowFrames()) {
            activePassiveScores.pollFirst();
        }
    }

    /** Check if passive score dropped below threshold during active challenge. */
    boolean passiveScoreFailedDuringActive(double thresholdActive) {
        if (activePassiveScores.size() < policy.passiveMinFrames()) {
            return false; // not enough frames yet
        }
        List<Double> snapshot = new ArrayList<>(activePassiveScores);
        double median = LivenessDecisionLogic.median(snapshot);
        return median < thresholdActive;
    }

    // v3: challenge progress tracking
    void recordChallengeProgress(ChallengeProgress progress) {
        challengeProgressEvents.add(progress);
        if (progress == ChallengeProgress.ACTION_DETECTED || progress == ChallengeProgress.HOLD_STILL) {
            actionDetectedThisChallenge = true;
        }
    }

    SessionSummary summarize(long durationMs) {
        return new SessionSummary(sessionId, workflow, outcome, escalated, lastMedianScore,
                padAttackType, challengeAttempts, challengesPassed, challengesFailed, durationMs,
                challengeProgressEvents());
    }

    // G7: per-session frame sampling counter
    boolean incrementAndCheckFrameSampling() {
        frameCounter++;
        return frameCounter % policy.frameSamplingRate() == 0;
    }

    long startedAtMillis() { return startedAtMillis; }
}
