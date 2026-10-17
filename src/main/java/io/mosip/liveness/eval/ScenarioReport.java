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
        Map<PresentationLabel, SpeciesApcer> apcerBySpecies,
        Map<IsoLevel, IsoLevelSummary> apcerByLevel) {

    /** ISO/IEC 30107-3 attack presentation levels. */
    public enum IsoLevel {
        LEVEL_A("Level A: 2D static (print, screen)"),
        LEVEL_B("Level B: 2D dynamic / mask (video, mask)"),
        LEVEL_C("Level C: 3D / volumetric (3D, mannequin)");

        private final String description;
        IsoLevel(String description) { this.description = description; }
        public String description() { return description; }

        public static IsoLevel of(PresentationLabel label) {
            return switch (label) {
                case PRINTED_PHOTO, SCREEN_REPLAY -> LEVEL_A;
                case VIDEO_REPLAY, MASK -> LEVEL_B;
                case THREE_D -> LEVEL_C;
                case BONA_FIDE -> throw new IllegalArgumentException("Bona fide is not an attack species");
            };
        }
    }

    /** Roll-up of APCER per ISO/IEC 30107-3 presentation attack level. */
    public record IsoLevelSummary(
            IsoLevel level,
            int presentations,
            int acceptedAsBonaFide,
            double apcer) {
        @Override
        public String toString() {
            return String.format("%.4f (%d/%d)", apcer, acceptedAsBonaFide, presentations);
        }
    }

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

    public ScenarioReport(
            double apcer,
            double bpcer,
            double acer,
            double livenessFar,
            double livenessFrr,
            double escalationRate,
            double avgLatencyMs,
            long totalPresentations,
            Map<PresentationLabel, SpeciesApcer> apcerBySpecies) {
        this(apcer, bpcer, acer, livenessFar, livenessFrr, escalationRate, avgLatencyMs,
                totalPresentations, apcerBySpecies, deriveLevelSummary(apcerBySpecies));
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
        if (apcerByLevel == null || apcerByLevel.isEmpty()) {
            apcerByLevel = Map.of();
        } else {
            EnumMap<IsoLevel, IsoLevelSummary> copy =
                    new EnumMap<>(IsoLevel.class);
            copy.putAll(apcerByLevel);
            apcerByLevel = Collections.unmodifiableMap(copy);
        }
    }

    static Map<IsoLevel, IsoLevelSummary> deriveLevelSummary(Map<PresentationLabel, SpeciesApcer> bySpecies) {
        if (bySpecies == null || bySpecies.isEmpty()) return Map.of();
        Map<IsoLevel, Integer> totalByLevel = new EnumMap<>(IsoLevel.class);
        Map<IsoLevel, Integer> acceptedByLevel = new EnumMap<>(IsoLevel.class);

        for (Map.Entry<PresentationLabel, SpeciesApcer> entry : bySpecies.entrySet()) {
            if (entry.getKey() == PresentationLabel.BONA_FIDE) continue;
            IsoLevel level = IsoLevel.of(entry.getKey());
            totalByLevel.merge(level, entry.getValue().presentations(), Integer::sum);
            acceptedByLevel.merge(level, entry.getValue().acceptedAsBonaFide(), Integer::sum);
        }

        Map<IsoLevel, IsoLevelSummary> summary = new EnumMap<>(IsoLevel.class);
        for (IsoLevel level : IsoLevel.values()) {
            int total = totalByLevel.getOrDefault(level, 0);
            int accepted = acceptedByLevel.getOrDefault(level, 0);
            double apcer = total == 0 ? 0.0 : accepted / (double) total;
            summary.put(level, new IsoLevelSummary(level, total, accepted, apcer));
        }
        return Collections.unmodifiableMap(summary);
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
        StringBuilder levels = new StringBuilder();
        for (IsoLevel lvl : IsoLevel.values()) {
            IsoLevelSummary row = apcerByLevel.get(lvl);
            if (row == null) continue;
            if (levels.length() > 0) levels.append(", ");
            levels.append(lvl.name()).append('=').append(row);
        }
        return String.format(
                "ScenarioReport[presentations=%d  APCER=%.4f  BPCER=%.4f  ACER=%.4f  pipelineFAR=%.4f  "
                        + "pipelineFRR=%.4f  escalationRate=%.4f  avgLatencyMs=%.1f  apcerBySpecies={%s}  apcerByLevel={%s}]",
                totalPresentations, apcer, bpcer, acer, livenessFar, livenessFrr, escalationRate,
                avgLatencyMs, species, levels);
    }
}
