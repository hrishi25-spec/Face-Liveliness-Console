package io.mosip.liveness.core;

/**
 * De-saturates an over-confident passive-liveness probability without moving
 * the decision operating point.
 *
 * <p><b>Why.</b> MiniFASNet-V2 is a strong, but badly calibrated, classifier:
 * on an ordinary genuine webcam face it emits a live-class probability of
 * 0.99–0.9999, so the passive score reads as a flat {@code 1.000} and carries
 * no information — the console's decision log shows the same number on every
 * frame while the underlying signal does move. That is textbook
 * over-confidence, and the standard remedy is a temperature applied to the
 * log-odds.</p>
 *
 * <p><b>Plain temperature scaling would break the threshold.</b> Dividing the
 * whole probability range by a temperature pulls every value toward 0.5,
 * including the configured {@code passiveThreshold} (0.80 → ~0.72), so the
 * operating point silently moves and every genuine face near the margin starts
 * escalating to an active challenge. Instead this compresses only the
 * <em>tail above</em> {@link #DEFAULT_PIVOT} and is anchored so the pivot maps
 * to itself:</p>
 *
 * <pre>
 *   p &lt;= pivot : p' = p                       (untouched — attacks stay low)
 *   p &gt;  pivot : z  = logit(p), z0 = logit(pivot)
 *                p' = sigmoid(z0 + (z - z0) / temperature)
 * </pre>
 *
 * <p>The map is continuous and strictly increasing, and it fixes the pivot, so
 * for any decision threshold {@code t <= pivot} the pass/fail verdict is
 * <b>identical</b> to the uncalibrated score. A stricter threshold
 * ({@code t > pivot}, e.g. the 0.82/0.85 per-workflow points) is applied to the
 * calibrated score and therefore demands a slightly higher raw confidence —
 * the same "recalibrate after a scorer change" caveat the threshold
 * documentation already states.</p>
 *
 * <p>Immutable and side-effect free so it can be unit-pinned and shared.</p>
 */
public final class ProbabilityCalibration {

    /**
     * Anchor of the compression: the operation is the identity at and below
     * this probability. Kept equal to the default passive threshold
     * ({@code LivenessConfig.DEFAULT_PASSIVE_THRESHOLD}) so the default
     * operating point is preserved exactly.
     */
    public static final double DEFAULT_PIVOT = 0.80;

    /** Temperature 1 leaves the probability untouched (no calibration). */
    public static final double IDENTITY_TEMPERATURE = 1.0;

    private final double pivot;
    private final double temperature;

    /**
     * @param pivot       probability at and below which the score is untouched,
     *                    strictly inside {@code (0, 1)} so its logit is finite
     * @param temperature divisor applied to the log-odds above the pivot;
     *                    {@code 1} is the identity, larger spreads more
     */
    public ProbabilityCalibration(double pivot, double temperature) {
        if (!(pivot > 0.0 && pivot < 1.0)) {
            throw new IllegalArgumentException("pivot must be in (0,1), was " + pivot);
        }
        if (!Double.isFinite(temperature) || temperature < IDENTITY_TEMPERATURE) {
            throw new IllegalArgumentException("temperature must be >= 1, was " + temperature);
        }
        this.pivot = pivot;
        this.temperature = temperature;
    }

    /** No calibration — the raw model probability (temperature 1). */
    public static ProbabilityCalibration identity() {
        return new ProbabilityCalibration(DEFAULT_PIVOT, IDENTITY_TEMPERATURE);
    }

    /** Calibration at {@link #DEFAULT_PIVOT} with the given temperature. */
    public static ProbabilityCalibration ofTemperature(double temperature) {
        return new ProbabilityCalibration(DEFAULT_PIVOT, temperature);
    }

    /** The anchor below which scores pass through unchanged. */
    public double pivot() {
        return pivot;
    }

    /** The configured temperature; 1 means no calibration. */
    public double temperature() {
        return temperature;
    }

    /** True when this instance would change at least some scores. */
    public boolean isActive() {
        return temperature > IDENTITY_TEMPERATURE;
    }

    /**
     * Calibrates one probability. Values at or below the pivot, and every value
     * when the temperature is 1, are returned unchanged. NaN passes through
     * (the caller's "no score" sentinel), and out-of-range inputs are clamped.
     */
    public double apply(double probability) {
        if (Double.isNaN(probability)) {
            return probability;
        }
        double p = Math.max(0.0, Math.min(1.0, probability));
        if (temperature <= IDENTITY_TEMPERATURE || p <= pivot || p >= 1.0) {
            return p;
        }
        double z0 = logit(pivot);
        double z = logit(p);
        return sigmoid(z0 + (z - z0) / temperature);
    }

    private static double logit(double p) {
        return Math.log(p / (1.0 - p));
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    @Override
    public String toString() {
        return "ProbabilityCalibration[pivot=" + pivot + ", temperature=" + temperature + "]";
    }
}
