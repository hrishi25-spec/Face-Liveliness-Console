package io.mosip.liveness.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Pure, unit-testable decision functions for the passive stage:
 * temporal median voting over a sliding window + threshold decision +
 * passive-to-active escalation rule.
 */
public final class LivenessDecisionLogic {

    public enum PassiveOutcome { PROCEED_PASSIVE, ESCALATE_ACTIVE, PAD_BLOCK }

    private LivenessDecisionLogic() { }

    /** Median of the collection; even sizes average the two middle values. */
    public static double median(Collection<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("median requires non-empty values");
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * Passive-stage decision for one assessment point.
     * @param medianScore temporal median of recent frame scores
     * @param threshold configured confidence threshold
     * @param padFlagged true when any windowed frame was flagged as an attack
     */
    public static PassiveOutcome decidePassive(double medianScore, double threshold, boolean padFlagged) {
        if (padFlagged) return PassiveOutcome.PAD_BLOCK;
        return medianScore >= threshold ? PassiveOutcome.PROCEED_PASSIVE : PassiveOutcome.ESCALATE_ACTIVE;
    }

    /**
     * Passive-stage decision over a rolling window of frame scores.
     *
     * <p><b>Cold start:</b> until {@code minFrames} scores have accumulated the
     * window is not decidable and this returns empty — the caller keeps asking
     * for frames ({@code retry_passive}) instead of passing or escalating a
     * session on one or two lucky/unlucky frames. Faces move, autofocus hunts
     * and exposure settles during the first second of a capture, so an early
     * single-frame verdict is noise, not signal.</p>
     *
     * <p><b>Median, not mean:</b> once warm, the <em>median</em> of the most
     * recent {@code windowFrames} scores is compared to the threshold. A median
     * rejects outlier frames (a blink, motion blur, a momentary exposure shift)
     * without needing a second model to explain them.</p>
     *
     * @param scores frame scores in chronological order (oldest first)
     * @param minFrames minimum scores required before any decision is possible
     * @param windowFrames sliding window size for the median (usually &gt;= minFrames)
     * @param threshold configured confidence threshold
     * @return the decision, or empty while the window is still warming up
     */
    public static Optional<PassiveOutcome> decidePassiveWindow(List<Double> scores,
                                                               int minFrames,
                                                               int windowFrames,
                                                               double threshold) {
        if (scores == null || scores.size() < Math.max(1, minFrames)) {
            return Optional.empty();
        }
        int w = Math.max(1, windowFrames);
        List<Double> window = scores.size() <= w
                ? scores
                : scores.subList(scores.size() - w, scores.size());
        return Optional.of(decidePassive(median(window), threshold, false));
    }
}
