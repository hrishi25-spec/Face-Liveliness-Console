package io.mosip.liveness.core;

import java.util.Objects;

/** Verdict of the presentation-attack detector for one frame. */
public final class PadVerdict {

    private final boolean attackDetected;
    private final PadAttackType attackType;   // null when no attack detected
    private final double confidence;          // 0..1

    private PadVerdict(boolean attackDetected, PadAttackType attackType, double confidence) {
        if (confidence < 0.0 || confidence > 1.0) throw new IllegalArgumentException("confidence out of range");
        this.attackDetected = attackDetected;
        this.attackType = attackType;
        this.confidence = confidence;
    }

    public static PadVerdict bonaFide(double confidence) {
        return new PadVerdict(false, null, confidence);
    }

    public static PadVerdict attack(PadAttackType type, double confidence) {
        return new PadVerdict(true, Objects.requireNonNull(type, "attackType"), confidence);
    }

    public boolean attackDetected() { return attackDetected; }
    public PadAttackType attackType() { return attackType; }
    public double confidence() { return confidence; }

    @Override
    public String toString() {
        return attackDetected ? "PadVerdict[ATTACK " + attackType + " conf=" + confidence + "]"
                              : "PadVerdict[bonaFide conf=" + confidence + "]";
    }
}
