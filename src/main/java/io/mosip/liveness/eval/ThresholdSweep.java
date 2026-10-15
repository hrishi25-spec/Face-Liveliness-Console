package io.mosip.liveness.eval;

import io.mosip.liveness.engine.LivenessDecisionLogic;

import java.util.ArrayList;
import java.util.List;

/**
 * Threshold calibration: sweeps the configured passive threshold over labelled
 * decision windows and reports BPCER / APCER / ACER at each operating point,
 * plus a recommended threshold.
 *
 * <p><b>Why a sweep instead of a guessed number.</b> A threshold is only
 * meaningful relative to the score distribution of the scorer that produced the
 * scores. The operating point is therefore derived, not asserted: bona-fide
 * windows come from real captured sessions ({@code frame_events}), attack
 * windows from real presentation-attack sessions when any exist, otherwise from
 * the clearly-labelled proxy corpus (see {@link ProxyPresentationCorpus}).</p>
 *
 * <p>Each window is evaluated with the <em>same</em>
 * {@link LivenessDecisionLogic#decidePassiveWindow} function the live decision
 * path uses (median over the sliding window, cold start at {@code minFrames}),
 * so the reported rates describe the deployed behaviour rather than a
 * single-frame approximation of it.</p>
 *
 * <p>Definitions (ISO/IEC 30107-3 style, computed here as user-error rates of
 * the <em>passive</em> stage only):</p>
 * <ul>
 *   <li><b>BPCER</b> — fraction of bona-fide windows that do NOT pass passive
 *       at this threshold (they would be escalated to an active challenge)</li>
 *   <li><b>APCER</b> — fraction of attack windows that DO pass passive (a miss;
 *       PAD may still stop the presentation before this point)</li>
 *   <li><b>ACER</b> — mean of the two</li>
 * </ul>
 */
public final class ThresholdSweep {

    /** Sweep grid: thresholds from 0.05 to 0.95 in 0.05 steps. */
    public static final double STEP = 0.05;

    private ThresholdSweep() { }

    /** One operating point of the sweep. */
    public record Row(
            double threshold,
            long bonaFideWindows,
            long bonaFidePassed,
            double bpcer,
            Long attackWindows,     // null when no attack samples exist
            Double apcer,            // null when unmeasured
            Double acer) {           // null when unmeasured
    }

    /** Full sweep output; serialized as-is by the calibration endpoint. */
    public record Result(
            double targetBpcer,
            int minFrames,
            int windowFrames,
            String scorer,
            boolean attackDataIsProxy,
            int excludedWindows,
            Double recommendedThreshold,
            String recommendationBasis,
            String note,
            List<Row> rows) { }

    /**
     * Runs the sweep.
     *
     * @param bonaFideWindows decision windows of genuine captures (scores, chronological)
     * @param attackWindows   decision windows of presentations, or empty
     * @param minFrames       cold-start size used by the live path
     * @param windowFrames    median window size used by the live path
     * @param targetBpcer     max acceptable bona-fide escalation rate
     * @param scorer          id of the scorer that produced the scores (audit)
     * @param attackDataIsProxy true when {@code attackWindows} came from the
     *                          simulated corpus rather than recorded captures
     */
    public static Result run(List<List<Double>> bonaFideWindows,
                             List<List<Double>> attackWindows,
                             int minFrames,
                             int windowFrames,
                             double targetBpcer,
                             String scorer,
                             boolean attackDataIsProxy) {
        int excluded = 0;
        List<List<Double>> usableBonaFide = new ArrayList<>();
        for (List<Double> w : bonaFideWindows) {
            if (w != null && w.size() >= minFrames) usableBonaFide.add(w);
            else excluded++;
        }
        List<List<Double>> usableAttack = new ArrayList<>();
        for (List<Double> w : attackWindows) {
            if (w != null && w.size() >= minFrames) usableAttack.add(w);
            else excluded++;
        }

        boolean hasAttackData = !usableAttack.isEmpty();
        List<Row> rows = new ArrayList<>();
        for (double t = STEP; t <= 1.0 - 1e-9; t += STEP) {
            double threshold = Math.round(t * 100.0) / 100.0;

            long bfTotal = usableBonaFide.size();
            long bfPassed = 0;
            for (List<Double> w : usableBonaFide) {
                if (passes(w, minFrames, windowFrames, threshold)) bfPassed++;
            }
            double bpcer = bfTotal == 0 ? 0.0 : (bfTotal - bfPassed) / (double) bfTotal;

            Long atkTotal = null;
            Double apcer = null;
            Double acer = null;
            if (hasAttackData) {
                atkTotal = (long) usableAttack.size();
                long atkPassed = 0;
                for (List<Double> w : usableAttack) {
                    if (passes(w, minFrames, windowFrames, threshold)) atkPassed++;
                }
                apcer = atkPassed / (double) usableAttack.size();
                acer = (apcer + bpcer) / 2.0;
            }
            rows.add(new Row(threshold, bfTotal, bfPassed, bpcer, atkTotal, apcer, acer));
        }

        Recommendation rec = recommend(rows, hasAttackData, !usableBonaFide.isEmpty(),
                targetBpcer, minFrames);

        String note;
        if (!hasAttackData) {
            note = "APCER unmeasured: no presentation-attack sessions exist in frame_events and the "
                    + "proxy corpus produced no scorable faces. BPCER-only recommendation — record "
                    + "physical print/screen-replay captures to measure APCER (docs/status-report.md §4).";
        } else if (attackDataIsProxy) {
            note = "Attack windows are SIMULATED print/screen-replay degradations of synthetic scenes, "
                    + "not physical captures; APCER figures are directional proxies only.";
        } else {
            note = "Attack windows come from recorded presentation-attack sessions.";
        }

        return new Result(targetBpcer, minFrames, windowFrames, scorer, attackDataIsProxy,
                excluded, rec.threshold, rec.basis, note, rows);
    }

    private record Recommendation(Double threshold, String basis) { }

    private static boolean passes(List<Double> window, int minFrames, int windowFrames, double threshold) {
        return LivenessDecisionLogic.decidePassiveWindow(window, minFrames, windowFrames, threshold)
                .orElse(LivenessDecisionLogic.PassiveOutcome.ESCALATE_ACTIVE)
                == LivenessDecisionLogic.PassiveOutcome.PROCEED_PASSIVE;
    }

    /**
     * Picks the operating point:
     * <ol>
     *   <li>the <em>highest</em> threshold that keeps BPCER &le; target with
     *       APCER = 0 (or APCER unmeasured) — stricter is safer, so within the
     *       user-experience budget we take the strictest point;</li>
     *   <li>failing that, the threshold with the minimum ACER;</li>
     *   <li>failing that (no row meets the target), no recommendation.</li>
     * </ol>
     */
    private static Recommendation recommend(List<Row> rows, boolean hasAttackData,
                                            boolean hasBonaFide, double targetBpcer, int minFrames) {
        if (!hasBonaFide) {
            return new Recommendation(null,
                    "No usable bona-fide windows — nothing to calibrate against yet");
        }
        Double best = null;
        for (Row r : rows) {
            boolean apcerOk = !hasAttackData || (r.apcer() != null && r.apcer() == 0.0);
            if (r.bpcer() <= targetBpcer + 1e-12 && apcerOk) {
                if (best == null || r.threshold() > best) best = r.threshold();
            }
        }
        if (best != null) {
            return new Recommendation(best, String.format(
                    "Highest threshold keeping BPCER <= %.0f%%%s (decision: median of %d-frame windows)",
                    targetBpcer * 100,
                    hasAttackData ? " with APCER = 0" : " (APCER unmeasured)",
                    minFrames));
        }
        if (hasAttackData) {
            Row minAcer = rows.stream()
                    .filter(r -> r.acer() != null)
                    .min(java.util.Comparator.comparingDouble(Row::acer))
                    .orElse(null);
            if (minAcer != null) {
                return new Recommendation(minAcer.threshold(), String.format(
                        "Minimum ACER (%.1f%%) — no threshold met the BPCER target with zero APCER",
                        minAcer.acer() * 100));
            }
        }
        return new Recommendation(null,
                String.format("No threshold meets the %.0f%% BPCER target on this data — collect more "
                        + "bona-fide windows or lower the target", targetBpcer * 100));
    }
}
