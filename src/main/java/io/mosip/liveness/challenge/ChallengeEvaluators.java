package io.mosip.liveness.challenge;

import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.ChallengeProgress;
import io.mosip.liveness.core.FaceSignals;

import java.util.List;
import java.util.Optional;

/**
 * Pure rule-based evaluators that confirm a requested action was performed by
 * a live subject, from the landmark signal sequence captured during the
 * challenge window. No extra ML model required — signals come from the
 * backend's landmark extraction.
 */
public final class ChallengeEvaluators {

    public static final double DEFAULT_BLINK_CLOSE_EAR = 0.22;
    public static final double DEFAULT_BLINK_OPEN_EAR = 0.27;
    public static final double DEFAULT_SMILE_THRESHOLD = 0.55;
    public static final double DEFAULT_TURN_DEGREES = 12.0;
    public static final double DEFAULT_GAZE_TOLERANCE = 0.35;
    /** Number of consecutive frames with action detected before HOLD_STILL. */
    public static final int ACTION_CONFIRM_FRAMES = 2;

    private ChallengeEvaluators() { }

    /** Evaluate whether the sequence of signals satisfies the challenge (batch mode). */
    public static boolean evaluate(Challenge challenge, List<FaceSignals> sequence) {
        if (sequence == null || sequence.isEmpty()) return false;
        return switch (challenge.type()) {
            case BLINK -> blinked(sequence);
            case SMILE -> smiled(sequence);
            case TURN_HEAD_LEFT -> turned(sequence, -DEFAULT_TURN_DEGREES);
            case TURN_HEAD_RIGHT -> turned(sequence, DEFAULT_TURN_DEGREES);
            case LOOK_DIRECTION -> looked(sequence,
                    challenge.parameters().getOrDefault("dirX", 1.0),
                    challenge.parameters().getOrDefault("dirY", 0.0));
            case LOOK_UP -> looked(sequence, 0.0, -1.0);
            case LOOK_DOWN -> looked(sequence, 0.0, 1.0);
            case LOOK_LEFT -> looked(sequence, -1.0, 0.0);
            case LOOK_RIGHT -> looked(sequence, 1.0, 0.0);
        };
    }

    /**
     * Evaluate a single frame against the active challenge and return the
     * progress state for real-time UI feedback.
     *
     * @return the challenge progress for this frame, and whether the action is detected
     */
    public static FrameChallengeResult evaluateFrame(Challenge challenge, FaceSignals signals,
                                                     int framesWithAction) {
        if (signals == null) return new FrameChallengeResult(false, ChallengeProgress.AWAITING_ACTION);

        boolean actionDetected = switch (challenge.type()) {
            case BLINK -> frameBlinked(signals);
            case SMILE -> frameSmiled(signals);
            case TURN_HEAD_LEFT -> frameTurned(signals, -DEFAULT_TURN_DEGREES);
            case TURN_HEAD_RIGHT -> frameTurned(signals, DEFAULT_TURN_DEGREES);
            case LOOK_DIRECTION -> frameLooked(signals,
                    challenge.parameters().getOrDefault("dirX", 1.0),
                    challenge.parameters().getOrDefault("dirY", 0.0));
            case LOOK_UP -> frameLooked(signals, 0.0, -1.0);
            case LOOK_DOWN -> frameLooked(signals, 0.0, 1.0);
            case LOOK_LEFT -> frameLooked(signals, -1.0, 0.0);
            case LOOK_RIGHT -> frameLooked(signals, 1.0, 0.0);
        };

        ChallengeProgress progress;
        if (!actionDetected) {
            progress = ChallengeProgress.AWAITING_ACTION;
        } else if (framesWithAction + 1 < ACTION_CONFIRM_FRAMES) {
            progress = ChallengeProgress.ACTION_DETECTED;
        } else {
            progress = ChallengeProgress.HOLD_STILL;
        }

        return new FrameChallengeResult(actionDetected, progress);
    }

    /** Result of evaluating a single frame during the active challenge. */
    public record FrameChallengeResult(boolean actionDetected, ChallengeProgress progress) { }

    // ---- single-frame evaluators (for frame-by-frame mode) ----

    static boolean frameBlinked(FaceSignals s) {
        var ear = s.minEyeAspectRatio();
        return ear.isPresent() && ear.get() < DEFAULT_BLINK_CLOSE_EAR;
    }

    static boolean frameSmiled(FaceSignals s) {
        var smile = s.smileScore();
        return smile.isPresent() && smile.get() > DEFAULT_SMILE_THRESHOLD;
    }

    static boolean frameTurned(FaceSignals s, double targetDegrees) {
        var yaw = s.yawDegrees();
        return yaw.isPresent() && (targetDegrees < 0 ? yaw.get() <= targetDegrees : yaw.get() >= targetDegrees);
    }

    static boolean frameLooked(FaceSignals s, double dirX, double dirY) {
        if (s.gazeX().isEmpty() || s.gazeY().isEmpty()) return false;
        return Math.abs(s.gazeX().get() - dirX) <= DEFAULT_GAZE_TOLERANCE
                && Math.abs(s.gazeY().get() - dirY) <= DEFAULT_GAZE_TOLERANCE;
    }

    // ---- batch evaluators (for validateChallenge batch mode) ----

    static boolean blinked(List<FaceSignals> seq) {
        Double minEar = null;
        int minIdx = -1;
        for (int i = 0; i < seq.size(); i++) {
            var ear = seq.get(i).minEyeAspectRatio();
            if (ear.isPresent() && (minEar == null || ear.get() < minEar)) {
                minEar = ear.get();
                minIdx = i;
            }
        }
        if (minIdx < 0 || minEar > DEFAULT_BLINK_CLOSE_EAR) return false;
        for (int j = minIdx + 1; j < seq.size(); j++) {
            var ear = seq.get(j).minEyeAspectRatio();
            if (ear.isPresent() && ear.get() > DEFAULT_BLINK_OPEN_EAR) return true;
        }
        return false;
    }

    static boolean smiled(List<FaceSignals> seq) {
        return seq.stream()
                .map(FaceSignals::smileScore)
                .flatMap(Optional::stream)
                .anyMatch(s -> s > DEFAULT_SMILE_THRESHOLD);
    }

    static boolean turned(List<FaceSignals> seq, double targetDegrees) {
        return seq.stream()
                .map(FaceSignals::yawDegrees)
                .flatMap(Optional::stream)
                .anyMatch(targetDegrees < 0 ? y -> y <= targetDegrees : y -> y >= targetDegrees);
    }

    static boolean looked(List<FaceSignals> seq, double dirX, double dirY) {
        long matches = seq.stream()
                .filter(s -> s.gazeX().isPresent())
                .filter(s -> Math.abs(s.gazeX().get() - dirX) <= DEFAULT_GAZE_TOLERANCE)
                .filter(s -> s.gazeY().isPresent())
                .filter(s -> Math.abs(s.gazeY().get() - dirY) <= DEFAULT_GAZE_TOLERANCE)
                .count();
        return matches >= Math.max(1, seq.size() / 2);
    }
}
