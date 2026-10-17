package io.mosip.liveness.core;

import java.util.Optional;

/** Per-frame facial signals produced by a backend (detection + landmarks). */
public final class FaceSignals {

    private final int faceCount;
    private final double qualityScore;          // 0..1
    private final Double eyeAspectRatioLeft;    // nullable when landmarks unavailable
    private final Double eyeAspectRatioRight;
    private final Double smileScore;            // 0..1
    private final Double yawDegrees;            // negative = left, positive = right
    private final Double pitchDegrees;
    private final Double gazeX;                 // -1..1 normalized gaze direction
    private final Double gazeY;
    private final Double depthScore;            // nullable when depth sensor unavailable
    private final Double irConfidence;          // nullable when IR sensor unavailable

    public FaceSignals(int faceCount, double qualityScore,
                       Double earLeft, Double earRight,
                       Double smileScore, Double yawDegrees, Double pitchDegrees,
                       Double gazeX, Double gazeY) {
        this(faceCount, qualityScore, earLeft, earRight, smileScore, yawDegrees, pitchDegrees, gazeX, gazeY, null, null);
    }

    public FaceSignals(int faceCount, double qualityScore,
                       Double earLeft, Double earRight,
                       Double smileScore, Double yawDegrees, Double pitchDegrees,
                       Double gazeX, Double gazeY,
                       Double depthScore, Double irConfidence) {
        if (faceCount < 0) throw new IllegalArgumentException("faceCount < 0");
        this.faceCount = faceCount;
        this.qualityScore = qualityScore;
        this.eyeAspectRatioLeft = earLeft;
        this.eyeAspectRatioRight = earRight;
        this.smileScore = smileScore;
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.gazeX = gazeX;
        this.gazeY = gazeY;
        this.depthScore = depthScore;
        this.irConfidence = irConfidence;
    }

    public static FaceSignals noFace(double qualityScore) {
        return new FaceSignals(0, qualityScore, null, null, null, null, null, null, null);
    }

    public static FaceSignals live(int faceCount, double qualityScore,
                                   double earBothEyes, double smileScore,
                                   double yawDegrees, double pitchDegrees,
                                   double gazeX, double gazeY) {
        return new FaceSignals(faceCount, qualityScore, earBothEyes, earBothEyes,
                smileScore, yawDegrees, pitchDegrees, gazeX, gazeY);
    }

    public static FaceSignals liveWithSensors(int faceCount, double qualityScore,
                                             double earBothEyes, double smileScore,
                                             double yawDegrees, double pitchDegrees,
                                             double gazeX, double gazeY,
                                             Double depthScore, Double irConfidence) {
        return new FaceSignals(faceCount, qualityScore, earBothEyes, earBothEyes,
                smileScore, yawDegrees, pitchDegrees, gazeX, gazeY, depthScore, irConfidence);
    }

    public int faceCount() { return faceCount; }
    public double qualityScore() { return qualityScore; }
    public Optional<Double> smileScore() { return Optional.ofNullable(smileScore); }
    public Optional<Double> yawDegrees() { return Optional.ofNullable(yawDegrees); }
    public Optional<Double> pitchDegrees() { return Optional.ofNullable(pitchDegrees); }
    public Optional<Double> gazeX() { return Optional.ofNullable(gazeX); }
    public Optional<Double> gazeY() { return Optional.ofNullable(gazeY); }
    public Optional<Double> depthScore() { return Optional.ofNullable(depthScore); }
    public Optional<Double> irConfidence() { return Optional.ofNullable(irConfidence); }

    /** Minimum open-eye estimate (EAR) across both eyes; empty when unavailable. */
    public Optional<Double> minEyeAspectRatio() {
        if (eyeAspectRatioLeft == null && eyeAspectRatioRight == null) return Optional.empty();
        if (eyeAspectRatioLeft == null) return Optional.of(eyeAspectRatioRight);
        if (eyeAspectRatioRight == null) return Optional.of(eyeAspectRatioLeft);
        return Optional.of(Math.min(eyeAspectRatioLeft, eyeAspectRatioRight));
    }

    public boolean hasLandmarks() {
        return minEyeAspectRatio().isPresent();
    }
}
