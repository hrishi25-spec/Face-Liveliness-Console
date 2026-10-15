package io.mosip.liveness.engine;

import io.mosip.liveness.core.ChallengeProgress;
import io.mosip.liveness.core.CombinedLivenessScore;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;

/** Result of {@code pushFrame}: status, liveness score, PAD flag, safe message. */
public final class FrameAssessment {

    private final AssessmentStatus status;
    private final LivenessErrorCode errorCode;
    private final double livenessScore;
    private final boolean padFlagged;
    private final PadAttackType padAttackType;
    private final String userMessage;
    private final ChallengeProgress challengeProgress;
    private final CombinedLivenessScore combinedScore;

    private FrameAssessment(AssessmentStatus status, LivenessErrorCode errorCode, double livenessScore,
                            boolean padFlagged, PadAttackType padAttackType, String userMessage,
                            ChallengeProgress challengeProgress, CombinedLivenessScore combinedScore) {
        this.status = status;
        this.errorCode = errorCode;
        this.livenessScore = livenessScore;
        this.padFlagged = padFlagged;
        this.padAttackType = padAttackType;
        this.userMessage = userMessage;
        this.challengeProgress = challengeProgress;
        this.combinedScore = combinedScore;
    }

    static FrameAssessment scoring(double score) {
        return new FrameAssessment(AssessmentStatus.SCORING, null, score, false, null, null, null, null);
    }

    static FrameAssessment passed(double score) {
        return new FrameAssessment(AssessmentStatus.PASSED, null, score, false, null, null, null, null);
    }

    static FrameAssessment bypassed() {
        return new FrameAssessment(AssessmentStatus.PASSED, null, Double.NaN, false, null,
                "Liveness verification disabled.", null, null);
    }

    static FrameAssessment escalated(double medianScore) {
        return new FrameAssessment(AssessmentStatus.ESCALATED_TO_ACTIVE, null, medianScore, false, null,
                "Please follow the on-screen instructions.", null, null);
    }

    static FrameAssessment challengeInProgress() {
        return new FrameAssessment(AssessmentStatus.CHALLENGE_IN_PROGRESS, null, Double.NaN, false, null,
                "Please perform the requested action.", ChallengeProgress.AWAITING_ACTION, null);
    }

    /** v3: Frame evaluated during active challenge with progress and combined score. */
    static FrameAssessment challengeEvaluated(ChallengeProgress progress, CombinedLivenessScore score) {
        String msg = switch (progress) {
            case AWAITING_ACTION -> "Please continue.";
            case ACTION_DETECTED -> "Action detected.";
            case HOLD_STILL -> "Hold still.";
        };
        return new FrameAssessment(AssessmentStatus.CHALLENGE_IN_PROGRESS, null,
                score.passiveComponent(), false, null, msg, progress, score);
    }

    static FrameAssessment padBlocked(PadVerdict verdict) {
        return new FrameAssessment(AssessmentStatus.PAD_BLOCKED, LivenessErrorCode.PAD_FAILURE,
                Double.NaN, true, verdict.attackType(),
                LivenessErrorCode.PAD_FAILURE.userMessage(), null, null);
    }

    static FrameAssessment failed(LivenessErrorCode code, double score) {
        return new FrameAssessment(AssessmentStatus.FAILED, code, score, false, null, code.userMessage(), null, null);
    }

    static FrameAssessment retryableError(LivenessErrorCode code) {
        return new FrameAssessment(AssessmentStatus.RETRYABLE_ERROR, code, Double.NaN, false, null,
                code.userMessage(), null, null);
    }

    /** v3: Session duration timeout. */
    static FrameAssessment sessionTimedOut() {
        return new FrameAssessment(AssessmentStatus.SESSION_TIMED_OUT,
                LivenessErrorCode.SESSION_TIMEOUT, Double.NaN, false, null,
                LivenessErrorCode.SESSION_TIMEOUT.userMessage(), null, null);
    }

    public AssessmentStatus status() { return status; }
    public LivenessErrorCode errorCode() { return errorCode; }
    public double livenessScore() { return livenessScore; }
    public boolean padFlagged() { return padFlagged; }
    public PadAttackType padAttackType() { return padAttackType; }
    public String userMessage() { return userMessage; }
    /** v3: real-time challenge progress, non-null only during CHALLENGE_IN_PROGRESS. */
    public ChallengeProgress challengeProgress() { return challengeProgress; }
    /** v3: combined passive+active score, non-null only during active challenge evaluation. */
    public CombinedLivenessScore combinedScore() { return combinedScore; }
}
