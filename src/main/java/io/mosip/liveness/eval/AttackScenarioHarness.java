package io.mosip.liveness.eval;

import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.engine.AssessmentStatus;
import io.mosip.liveness.engine.FaceLivenessEngine;
import io.mosip.liveness.engine.FrameAssessment;
import io.mosip.liveness.engine.ValidationResult;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Function;

/**
 * Automated attack-scenario test harness for CI/regression runs.
 *
 * Generates labeled presentations (bona fide + printed photo + screen replay +
 * video replay, with imperfect-classifier simulation and varied quality), runs
 * them end-to-end through the real engine pipeline, and reports APCER / BPCER
 * / ACER plus pipeline-level metrics. Swap the MockLivenessBackend for recorded
 * device captures to evaluate a real model.
 */
public final class AttackScenarioHarness {

    /** Result of one presentation run through the full pipeline. */
    public record PresentationResult(
            PresentationLabel label,
            boolean acceptedAsBonaFide,
            boolean padBlocked,
            boolean escalatedToActive) { }

    /** Simulated classifier imperfection rates; tune to match the deployed model. */
    public static final double SIMULATED_ATTACK_MISS_RATE = 0.03;   // attack evades PAD
    public static final double SIMULATED_BONAFIDE_FP_RATE = 0.01;   // bona fide flagged as attack

    private static final int FRAMES_PER_PASSIVE_RUN = 8;

    private final LivenessConfig config;
    private final long seed;
    /** Optional real backend factory; null means the scripted mock. */
    private final Function<Long, LivenessBackend> backendFactory;
    private int frameCounter;

    public AttackScenarioHarness(LivenessConfig config, long seed) {
        this(config, seed, null);
    }

    /**
     * @param backendFactory when present, used instead of the scripted
     *   {@link MockLivenessBackend} — inject a real backend (e.g.
     *   {@code OnnxMiniFasNetBackend}) to evaluate genuine model behaviour.
     *   Attack scripting ({@code Subject}) only exists on the mock, so with an
     *   injected backend an escalation is recorded as an escalation rather than
     *   being carried through a scripted active challenge.
     */
    public AttackScenarioHarness(LivenessConfig config, long seed,
                                 Function<Long, LivenessBackend> backendFactory) {
        this.config = config;
        this.seed = seed;
        this.backendFactory = backendFactory;
    }

    /**
     * Runs {@code presentationsPerLabel} presentations for every label.
     * @return one result per presentation, in deterministic seeded order
     */
    public List<PresentationResult> run(int presentationsPerLabel) {
        List<PresentationResult> results = new ArrayList<>();
        for (PresentationLabel label : PresentationLabel.values()) {
            SplittableRandom rng = new SplittableRandom(seed + 7919L * label.ordinal());
            for (int i = 0; i < presentationsPerLabel; i++) {
                results.add(runOne(label, rng, seed * 31 + i));
            }
        }
        return results;
    }

    PresentationResult runOne(PresentationLabel label, SplittableRandom rng, long streamSeed) {
        MockLivenessBackend mock = new MockLivenessBackend(new SplittableRandom(streamSeed));
        boolean scripted = backendFactory == null;
        if (scripted) {
            configure(mock.subject(), label, rng);
        }
        LivenessBackend backend = scripted ? mock : backendFactory.apply(streamSeed);

        FaceLivenessEngine engine = new FaceLivenessEngine(config, backend, AuditLogger.noop(),
                new MetricsCollector(), Clock.systemUTC());
        String sid = engine.initSession(io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION);

        boolean accepted = false;
        boolean padBlocked = false;
        boolean escalated = false;

        try {
            for (int f = 0; f < FRAMES_PER_PASSIVE_RUN && !accepted && !padBlocked; f++) {
                FrameAssessment a;
                try {
                    a = engine.pushFrame(sid, nextFrame());
                } catch (io.mosip.liveness.core.LivenessException e) {
                    // Session reached a terminal state (e.g. max retries exhausted
                    // or PAD blocked on a prior frame) — stop pushing frames.
                    break;
                }
                if (a.status() == AssessmentStatus.PASSED) {
                    accepted = true;
                } else if (a.status() == AssessmentStatus.ESCALATED_TO_ACTIVE) {
                    escalated = true;
                    accepted = scripted && runActiveStage(engine, sid, mock);
                } else if (a.status() == AssessmentStatus.PAD_BLOCKED) {
                    padBlocked = true;
                }
            }
        } finally {
            engine.closeSession(sid);
        }

        return new PresentationResult(label, accepted && !padBlocked, padBlocked, escalated);
    }

    private boolean runActiveStage(FaceLivenessEngine engine, String sid, MockLivenessBackend mock) {
        int guard = 0;
        while (guard++ < 12) {
            Challenge challenge = engine.requestChallenge(sid);
            ValidationResult r = engine.validateChallenge(sid, framesFor(challenge, mock));
            if (r.passed()) return true;
            if (r.hardFailure()) return false;
        }
        return false;
    }

    private static FaceSignals liveSignals(double ear, double smile, double yaw, double gazeX, double gazeY) {
        return FaceSignals.live(1, 0.92, ear, smile, yaw, 0, gazeX, gazeY);
    }

    /** Builds a landmark-signal sequence that genuinely performs the challenge. */
    List<Frame> framesFor(Challenge challenge, MockLivenessBackend mock) {
        mock.clearSignalOverrides();
        List<Frame> frames = new ArrayList<>();
        switch (challenge.type()) {
            case BLINK -> {
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0.05, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.08, 0.05, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.08, 0.05, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0.05, 0, 0, 0));
            }
            case SMILE -> {
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0.10, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0.85, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0.85, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0.10, 0, 0, 0));
            }
            case TURN_HEAD_LEFT -> {
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, -10, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, -18, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, -18, 0, 0));
            }
            case TURN_HEAD_RIGHT -> {
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 10, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 18, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 18, 0, 0));
            }
            case LOOK_DIRECTION -> {
                double dx = challenge.parameters().getOrDefault("dirX", 1.0);
                double dy = challenge.parameters().getOrDefault("dirY", 0.0);
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 0, dx, dy));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 0, dx, dy));
                add(frames);
                mock.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
            }
            case LOOK_UP -> gazeFrames(frames, mock, 0.0, -1.0);
            case LOOK_DOWN -> gazeFrames(frames, mock, 0.0, 1.0);
            case LOOK_LEFT -> gazeFrames(frames, mock, -1.0, 0.0);
            case LOOK_RIGHT -> gazeFrames(frames, mock, 1.0, 0.0);
        }
        return frames;
    }

    private void gazeFrames(List<Frame> frames, MockLivenessBackend mock, double dx, double dy) {
        add(frames);
        mock.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
        add(frames);
        mock.enqueueSignal(liveSignals(0.34, 0, 0, dx, dy));
        add(frames);
        mock.enqueueSignal(liveSignals(0.34, 0, 0, dx, dy));
        add(frames);
        mock.enqueueSignal(liveSignals(0.34, 0, 0, 0, 0));
    }

    private void configure(MockLivenessBackend.Subject subject, PresentationLabel label, SplittableRandom rng) {
        switch (label) {
            case BONA_FIDE -> {
                // ~30% low-quality genuine users: forces passive->active escalation path
                boolean lowQuality = rng.nextDouble() < 0.30;
                subject.livenessScore = lowQuality ? 0.62 : 0.90;
                subject.scoreNoise = 0.02;
                subject.padVerdict = rng.nextDouble() < SIMULATED_BONAFIDE_FP_RATE
                        ? PadVerdict.attack(PadAttackType.OTHER, 0.60)     // false positive
                        : PadVerdict.bonaFide(0.97);
            }
            case PRINTED_PHOTO, SCREEN_REPLAY, VIDEO_REPLAY, MASK, THREE_D -> {
                if (rng.nextDouble() < SIMULATED_ATTACK_MISS_RATE) {
                    // Attack evades the PAD classifier — looks like a decent capture
                    subject.livenessScore = 0.88;
                    subject.scoreNoise = 0.02;
                    subject.padVerdict = PadVerdict.bonaFide(0.55);
                } else {
                    double confidence = 0.90 + rng.nextDouble() * 0.09;
                    PadAttackType type = switch (label) {
                        case PRINTED_PHOTO -> PadAttackType.PRINTED_PHOTO;
                        case SCREEN_REPLAY -> PadAttackType.SCREEN_REPLAY;
                        case MASK -> PadAttackType.MASK;
                        case THREE_D -> PadAttackType.THREE_D;
                        default -> PadAttackType.VIDEO_REPLAY;
                    };
                    subject.attack(type, confidence);
                }
            }
        }
    }

    private Frame nextFrame() {
        return Frame.of(new byte[256], 16, 16, Frame.Format.RGB_GRAY,
                System.nanoTime(), frameCounter++);
    }

    private void add(List<Frame> acc) {
        acc.add(Frame.of(new byte[256], 16, 16, Frame.Format.RGB_GRAY, System.nanoTime(), frameCounter++));
    }

    // ---- aggregation ----

    /**
     * Computes the ISO/IEC 30107-3 style report from raw results — aggregate
     * APCER/BPCER/ACER plus the per-PAI-species APCER breakdown
     * ({@link ScenarioReport#apcerBySpecies()}).
     */
    public static ScenarioReport report(List<PresentationResult> results) {
        Map<PresentationLabel, Integer> totals = new EnumMap<>(PresentationLabel.class);
        Map<PresentationLabel, Integer> accepted = new EnumMap<>(PresentationLabel.class);
        int escalations = 0;
        for (PresentationResult r : results) {
            totals.merge(r.label(), 1, Integer::sum);
            if (r.acceptedAsBonaFide()) accepted.merge(r.label(), 1, Integer::sum);
            if (r.escalatedToActive()) escalations++;
        }

        int attacksTotal = results.size() - totals.getOrDefault(PresentationLabel.BONA_FIDE, 0);
        int attacksAccepted = 0;
        for (PresentationLabel l : PresentationLabel.values()) {
            if (l != PresentationLabel.BONA_FIDE) attacksAccepted += accepted.getOrDefault(l, 0);
        }
        int bonaTotal = totals.getOrDefault(PresentationLabel.BONA_FIDE, 0);
        int bonaRejected = bonaTotal - accepted.getOrDefault(PresentationLabel.BONA_FIDE, 0);

        // Per-species rows (ISO/IEC 30107-3 reports APCER per PAI species);
        // bona fide is excluded — its error rate is BPCER, not an APCER.
        Map<PresentationLabel, ScenarioReport.SpeciesApcer> bySpecies =
                new EnumMap<>(PresentationLabel.class);
        for (PresentationLabel label : PresentationLabel.values()) {
            if (label == PresentationLabel.BONA_FIDE) {
                continue;
            }
            int speciesTotal = totals.getOrDefault(label, 0);
            int speciesAccepted = accepted.getOrDefault(label, 0);
            bySpecies.put(label, new ScenarioReport.SpeciesApcer(
                    label, speciesTotal, speciesAccepted,
                    PadMetrics.apcer(speciesAccepted, speciesTotal)));
        }

        double apcer = PadMetrics.apcer(attacksAccepted, attacksTotal);
        double bpcer = PadMetrics.bpcer(bonaRejected, bonaTotal);
        double n = Math.max(1, results.size());
        return new ScenarioReport(apcer, bpcer, PadMetrics.acer(apcer, bpcer),
                apcer, bpcer, escalations / n, 0 /* latency measured per-presentation upstream */,
                results.size(), bySpecies);
    }
}
