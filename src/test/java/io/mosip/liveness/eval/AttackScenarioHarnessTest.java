package io.mosip.liveness.eval;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the full pipeline against scripted bona fide and attack presentations
 * and reports ISO/IEC 30107-3 metrics (aggregate APCER / BPCER / ACER, the
 * per-PAI-species APCER breakdown, and the passive-to-active escalation rate).
 *
 * With MockLivenessBackend these numbers characterize the decision logic, not
 * a trained model; swap in a recorded-capture backend to evaluate a real one.
 */
class AttackScenarioHarnessTest {

    private static final int PRESENTATIONS_PER_LABEL = 60;
    private static final long SEED = 20260825L;

    @Test
    void reportMetricsWithinAcceptanceBounds() {
        AttackScenarioHarness harness = new AttackScenarioHarness(
                io.mosip.liveness.config.LivenessConfig.builder().build(), SEED);

        long t0 = System.nanoTime();
        List<AttackScenarioHarness.PresentationResult> results = harness.run(PRESENTATIONS_PER_LABEL);
        double wallMs = (System.nanoTime() - t0) / 1_000_000.0;

        ScenarioReport report = AttackScenarioHarness.report(results);

        System.out.println("==== ISO/IEC 30107-3 style evaluation (mock backend) ====");
        System.out.println(report);
        System.out.printf("wall time: %.0f ms for %d presentations (%.2f ms/presentation)%n",
                wallMs, results.size(), wallMs / results.size());

        Map<PresentationLabel, Integer> byLabel = new EnumMap<>(PresentationLabel.class);
        results.forEach(r -> byLabel.merge(r.label(), 1, Integer::sum));
        System.out.println("presentations per label: " + byLabel);

        // Bounds reflect simulated classifier error rates (3% attack miss, 1% bona fide FP)
        // plus statistical slack for 60 presentations per label.
        assertTrue(report.apcer() <= 0.08, "APCER too high: " + report.apcer());
        assertTrue(report.bpcer() <= 0.08, "BPCER too high: " + report.bpcer());
        assertTrue(report.acer() <= 0.05, "ACER too high: " + report.acer());

        // The escalation path must be exercised (30% of genuine users are low-quality).
        // With 60 bona fide out of 240 total presentations, max bona-fide-only
        // escalation rate is 30% × 60/240 ≈ 7.5%; attacks that evade PAD also
        // escalate if their liveness score is low.
        assertTrue(report.escalationRate() >= 0.05,
                "expected meaningful passive->active escalation rate, got " + report.escalationRate());
    }

    @Test
    void apcerBreakdownGroupsAttackSpeciesFromAHarnessRun() {
        AttackScenarioHarness harness = new AttackScenarioHarness(
                io.mosip.liveness.config.LivenessConfig.builder().build(), SEED);
        List<AttackScenarioHarness.PresentationResult> results = harness.run(PRESENTATIONS_PER_LABEL);
        ScenarioReport report = AttackScenarioHarness.report(results);

        // Exactly the three attack species; bona fide never carries an APCER row.
        assertEquals(Set.of(PresentationLabel.PRINTED_PHOTO, PresentationLabel.SCREEN_REPLAY,
                        PresentationLabel.VIDEO_REPLAY),
                report.apcerBySpecies().keySet());

        Map<PresentationLabel, Integer> byLabel = new EnumMap<>(PresentationLabel.class);
        results.forEach(r -> byLabel.merge(r.label(), 1, Integer::sum));
        for (PresentationLabel label : PresentationLabel.values()) {
            if (label == PresentationLabel.BONA_FIDE) {
                continue;
            }
            ScenarioReport.SpeciesApcer row = report.apcerBySpecies().get(label);
            assertEquals(PRESENTATIONS_PER_LABEL, row.presentations(),
                    "every species must see its own presentations: " + label);
            assertEquals(byLabel.get(label).intValue(), row.presentations());
            // The row's APCER is its own accepted/presentations, not the aggregate.
            assertEquals(PadMetrics.apcer(row.acceptedAsBonaFide(), row.presentations()),
                    row.apcer(), 1e-9, label + " APCER must be per-species");
        }

        // The aggregate is the presentation-weighted mean of the species rows.
        int speciesTotal = report.apcerBySpecies().values().stream()
                .mapToInt(ScenarioReport.SpeciesApcer::presentations).sum();
        int speciesAccepted = report.apcerBySpecies().values().stream()
                .mapToInt(ScenarioReport.SpeciesApcer::acceptedAsBonaFide).sum();
        assertEquals(results.size() - byLabel.get(PresentationLabel.BONA_FIDE), speciesTotal);
        assertEquals(PRESENTATIONS_PER_LABEL * 3, speciesTotal);
        assertEquals(speciesAccepted / (double) speciesTotal, report.apcer(), 1e-9,
                "aggregate APCER must equal the weighted mean of the per-species rows");
    }

    @Test
    void perSpeciesApcerIsComputedPerLabelNotAggregate() {
        // Hand-built results with wildly different per-species outcomes:
        // PRINTED_PHOTO evades on 1 of 3, the other attack species are caught cold.
        List<AttackScenarioHarness.PresentationResult> results = List.of(
                new AttackScenarioHarness.PresentationResult(
                        PresentationLabel.PRINTED_PHOTO, true, false, false),
                new AttackScenarioHarness.PresentationResult(
                        PresentationLabel.PRINTED_PHOTO, false, true, false),
                new AttackScenarioHarness.PresentationResult(
                        PresentationLabel.PRINTED_PHOTO, false, true, false),
                new AttackScenarioHarness.PresentationResult(
                        PresentationLabel.SCREEN_REPLAY, false, true, false),
                new AttackScenarioHarness.PresentationResult(
                        PresentationLabel.VIDEO_REPLAY, false, true, false),
                new AttackScenarioHarness.PresentationResult(
                        PresentationLabel.BONA_FIDE, true, false, false),
                new AttackScenarioHarness.PresentationResult(
                        PresentationLabel.BONA_FIDE, false, false, false));
        ScenarioReport report = AttackScenarioHarness.report(results);

        ScenarioReport.SpeciesApcer printed =
                report.apcerBySpecies().get(PresentationLabel.PRINTED_PHOTO);
        assertEquals(3, printed.presentations());
        assertEquals(1, printed.acceptedAsBonaFide());
        assertEquals(1 / 3.0, printed.apcer(), 1e-9);
        assertEquals(0.0,
                report.apcerBySpecies().get(PresentationLabel.SCREEN_REPLAY).apcer(), 1e-9);
        assertEquals(0.0,
                report.apcerBySpecies().get(PresentationLabel.VIDEO_REPLAY).apcer(), 1e-9);
        assertFalse(report.apcerBySpecies().containsKey(PresentationLabel.BONA_FIDE),
                "APCER is defined over attack species only — bona fide feeds BPCER");

        // Aggregate = presentation-weighted mean across species = 1 of 5 attacks.
        assertEquals(1 / 5.0, report.apcer(), 1e-9);
        assertEquals(7, report.totalPresentations());
        assertEquals(1 / 2.0, report.bpcer(), 1e-9);   // 1 of 2 bona fide rejected

        // The breakdown ships as an immutable view.
        assertThrows(UnsupportedOperationException.class,
                () -> report.apcerBySpecies().remove(PresentationLabel.PRINTED_PHOTO));
    }

    @Test
    void emptyRunStillReportsAllSpeciesRowsAsZero() {
        ScenarioReport report = AttackScenarioHarness.report(List.of());

        assertEquals(3, report.apcerBySpecies().size());
        for (ScenarioReport.SpeciesApcer row : report.apcerBySpecies().values()) {
            assertEquals(0, row.presentations());
            assertEquals(0, row.acceptedAsBonaFide());
            assertEquals(0.0, row.apcer(), 1e-9,
                    "zero presentations must read 0.0, not NaN");
        }
        assertEquals(0.0, report.apcer(), 1e-9);
        assertTrue(report.toString().contains("apcerBySpecies={PRINTED_PHOTO=0.0000 (0/0), "
                        + "SCREEN_REPLAY=0.0000 (0/0), VIDEO_REPLAY=0.0000 (0/0)}"),
                "toString must expose the breakdown in enum order: " + report);
    }
}
