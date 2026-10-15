package io.mosip.liveness.eval;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Aggregated results of one attack-scenario evaluation run.
 *
 * <p>ISO/IEC 30107-3 reports APCER <em>per PAI species</em>, so alongside the
 * aggregate {@link #apcer()} the report carries {@link #apcerBySpecies()}: one
 * {@link SpeciesApcer} row per attack {@link PresentationLabel} with its
 * presentation count, how many were accepted as bona fide, and that species'
 * APCER. The aggregate is the presentation-weighted mean of those rows.
 * BPCER stays a single value — the bona fide species has no APCER.</p>
 *
 * @param apcerBySpecies per-species APCER rows, keyed by attack
 *                       {@link PresentationLabel} (never includes
 *                       {@code BONA_FIDE}); immutable
 */
public record ScenarioReport(
        double apcer,
        double bpcer,
        double acer,
        double livenessFar,
        double livenessFrr,
        double escalationRate,
        double avgLatencyMs,
        long totalPresentations,
        Map<PresentationLabel, SpeciesApcer> apcerBySpecies) {

    /** One PAI species' slice of the run (ISO/IEC 30107-3 per-species APCER). */
    public record SpeciesApcer(
            PresentationLabel species,
            int presentations,
            int acceptedAsBonaFide,
            double apcer) {

        @Override
        public String toString() {
            return String.format("%.4f (%d/%d)", apcer, acceptedAsBonaFide, presentations);
        }
    }

    public ScenarioReport {
        if (apcerBySpecies == null || apcerBySpecies.isEmpty()) {
            apcerBySpecies = Map.of();
        } else {
            EnumMap<PresentationLabel, SpeciesApcer> copy =
                    new EnumMap<>(PresentationLabel.class);
            copy.putAll(apcerBySpecies);
            apcerBySpecies = Collections.unmodifiableMap(copy);
        }
    }

    @Override
    public String toString() {
        StringBuilder species = new StringBuilder();
        for (PresentationLabel label : PresentationLabel.values()) {
            SpeciesApcer row = apcerBySpecies.get(label);
            if (row == null) {
                continue;
            }
            if (species.length() > 0) {
                species.append(", ");
            }
            species.append(label.name()).append('=').append(row);
        }
        return String.format(
                "ScenarioReport[presentations=%d  APCER=%.4f  BPCER=%.4f  ACER=%.4f  pipelineFAR=%.4f  "
                        + "pipelineFRR=%.4f  escalationRate=%.4f  avgLatencyMs=%.1f  apcerBySpecies={%s}]",
                totalPresentations, apcer, bpcer, acer, livenessFar, livenessFrr, escalationRate,
                avgLatencyMs, species);
    }
}
