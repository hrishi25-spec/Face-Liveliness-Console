package io.mosip.liveness.eval;

/**
 * Standard PAD metrics per ISO/IEC 30107-3:
 * APCER — proportion of attack presentations classified as bona fide;
 * BPCER — proportion of bona fide presentations classified as attacks;
 * ACER  — arithmetic mean of the two.
 * Also FAR/FRR for the liveness decision at a configured threshold.
 */
public final class PadMetrics {

    private PadMetrics() { }

    public static double apcer(int attacksAcceptedAsBonaFide, int totalAttacks) {
        requireNonNegative(attacksAcceptedAsBonaFide, totalAttacks);
        return totalAttacks == 0 ? 0.0 : (double) attacksAcceptedAsBonaFide / totalAttacks;
    }

    public static double bpcer(int bonaFideRejected, int totalBonaFide) {
        requireNonNegative(bonaFideRejected, totalBonaFide);
        return totalBonaFide == 0 ? 0.0 : (double) bonaFideRejected / totalBonaFide;
    }

    public static double acer(double apcer, double bpcer) {
        return (apcer + bpcer) / 2.0;
    }

    /** False accept rate of the full liveness pipeline at the configured threshold. */
    public static double far(int impostorsAccepted, int totalImpostors) {
        return apcer(impostorsAccepted, totalImpostors);
    }

    /** False reject rate of the full liveness pipeline at the configured threshold. */
    public static double frr(int genuineRejected, int totalGenuine) {
        return bpcer(genuineRejected, totalGenuine);
    }

    private static void requireNonNegative(int hits, int total) {
        if (hits < 0 || total < 0 || hits > total) {
            throw new IllegalArgumentException("invalid counts: " + hits + "/" + total);
        }
    }
}
