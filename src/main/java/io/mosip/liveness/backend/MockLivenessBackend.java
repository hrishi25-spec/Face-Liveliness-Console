package io.mosip.liveness.backend;

import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;

import java.util.Map;
import java.util.SplittableRandom;

/**
 * Scriptable backend for tests, CI regression runs and the attack-scenario
 * harness. A mutable {@link Subject} simulates what the camera sees; tests
 * drive it directly ({@code backend.subject().blink()} etc.).
 */
public class MockLivenessBackend implements LivenessBackend {

    /** Simulated scene state. */
    public static class Subject {
        public boolean facePresent = true;
        public int faceCount = 1;
        public double quality = 0.92;
        public boolean blinking;
        public double smile = 0.05;
        public double yawDegrees;
        public double pitchDegrees;
        public double gazeX;
        public double gazeY;
        public double livenessScore = 0.93;
        public double scoreNoise = 0.01;
        public PadVerdict padVerdict = PadVerdict.bonaFide(0.99);
        public int openEar = 34;   // EAR x100 when eyes open
        public int closedEar = 8;  // EAR x100 when eyes closed

        public void blink() { blinking = true; }
        public void openEyes() { blinking = false; }
        public void smileBig() { smile = 0.85; }
        public void neutralFace() { smile = 0.05; }
        public void turnLeft(double deg) { yawDegrees = -Math.abs(deg); }
        public void turnRight(double deg) { yawDegrees = Math.abs(deg); }
        public void lookAt(double x, double y) { gazeX = x; gazeY = y; }
        public void attack(PadAttackType type, double confidence) {
            padVerdict = PadVerdict.attack(type, confidence);
            livenessScore = Math.min(livenessScore, 0.35);
        }
        public void resetPose() {
            blinking = false; smile = 0.05; yawDegrees = 0; pitchDegrees = 0; gazeX = 0; gazeY = 0;
        }
    }

    private final Subject subject = new Subject();
    private final SplittableRandom random;
    private final java.util.ArrayDeque<FaceSignals> signalQueue = new java.util.ArrayDeque<>();

    public MockLivenessBackend() { this(new SplittableRandom(42)); }
    public MockLivenessBackend(SplittableRandom random) { this.random = random; }

    public Subject subject() { return subject; }

    /**
     * Push signals that {@link #analyzeFrame} will return in FIFO order,
     * overriding the subject-based signals.  Used by tests that need per-frame
     * signal sequences for challenge evaluation.
     */
    public void enqueueSignal(FaceSignals s) { signalQueue.addLast(s); }

    /** Clear any queued per-frame signals. */
    public void clearSignalOverrides() { signalQueue.clear(); }

    @Override public String id() { return "mock"; }

    @Override public void initialize(Map<String, String> options) { /* no-op */ }

    @Override
    public FaceSignals analyzeFrame(Frame frame) {
        if (!signalQueue.isEmpty()) {
            return signalQueue.pollFirst();
        }
        if (!subject.facePresent || subject.faceCount == 0) {
            return FaceSignals.noFace(subject.quality);
        }
        return FaceSignals.live(
                subject.faceCount,
                subject.quality,
                (subject.blinking ? subject.closedEar : subject.openEar) / 100.0,
                subject.smile,
                subject.yawDegrees,
                subject.pitchDegrees,
                subject.gazeX,
                subject.gazeY);
    }

    @Override
    public double scorePassiveLiveness(Frame frame, FaceSignals signals) {
        double noise = subject.scoreNoise <= 0 ? 0 : (random.nextDouble() - 0.5) * 2 * subject.scoreNoise;
        return clamp01(subject.livenessScore + noise);
    }

    @Override
    public PadVerdict assessPad(Frame frame, FaceSignals signals) {
        return subject.padVerdict;
    }

    @Override public void shutdown() { /* no-op */ }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
