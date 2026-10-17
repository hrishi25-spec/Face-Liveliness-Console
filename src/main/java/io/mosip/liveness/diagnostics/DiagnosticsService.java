package io.mosip.liveness.diagnostics;

import io.mosip.liveness.diagnostics.DiagnosticsSnapshot.FrameSample;
import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.services.PassiveScoringService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostic mode (opt-in, local) — orchestration spec §10: retains the raw
 * scores, per-frame timings, FPS and the scorer delegate (the "delegate
 * used") so the local-only debug panel can show them. Still no pixels: the
 * input is the decision DTO, which never carries frame bytes.
 *
 * <p>Opt-in is fail-closed on both axes: with the flag off
 * ({@code mosip.liveness.diagnostics-enabled}, default {@code false}) nothing
 * is ever retained — an opt-in that silently buffered would be indistinguishable
 * from always-on — and {@link #snapshot()} returns the empty
 * {@link DiagnosticsSnapshot#disabled()} payload rather than a stale window.
 * The controller adds the second gate: loopback callers only.
 *
 * <p>Bounded by construction: a fixed-capacity ring of samples (no unbounded
 * growth on a long-running service) and a sliding 5 s FPS window. The clock is
 * injected so the window is testable without sleeping.
 */
@Component
public class DiagnosticsService {

    /** Frames retained for aggregation and the panel's table. */
    static final int DEFAULT_CAPACITY = 120;

    /** Rows the snapshot ships — the panel does not need the whole ring. */
    static final int SNAPSHOT_ROWS = 30;

    /** FPS is measured over this sliding window. */
    static final long FPS_WINDOW_MS = 5000L;

    private final boolean enabled;
    private final Clock clock;
    private final int capacity;
    private final PassiveScoringService passiveScorer;

    /** Guards {@link #samples}, {@link #frameCount} and {@link #scorer}. */
    private final Object lock = new Object();
    private final ArrayDeque<FrameSample> samples = new ArrayDeque<>();
    private long frameCount;
    private String scorer;

    @Autowired
    public DiagnosticsService(
            @Value("${mosip.liveness.diagnostics-enabled:false}") boolean enabled,
            PassiveScoringService passiveScorer) {
        this(enabled, Clock.systemUTC(), DEFAULT_CAPACITY, passiveScorer);
    }

    DiagnosticsService(boolean enabled, Clock clock, int capacity,
                       PassiveScoringService passiveScorer) {
        this.enabled = enabled;
        this.clock = clock;
        this.capacity = capacity;
        this.passiveScorer = passiveScorer;
    }

    /** Whether the mode was opted in at startup. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Retain one frame's diagnostics after it was processed. Fail closed:
     * when the mode is off nothing is stored, not even counters.
     *
     * @param result       the frame's decision (scores/quality/flag/action)
     * @param elapsedNanos end-to-end cost of processing that frame
     */
    public void recordFrame(FrameProcessResult result, long elapsedNanos) {
        if (!enabled || result == null) {
            return;
        }
        FrameSample sample = new FrameSample(
                clock.instant(),
                result.getLivenessScore(),
                result.getFaceQuality(),
                Boolean.TRUE.equals(result.getPadFlag()),
                Math.max(0L, TimeUnit.NANOSECONDS.toMillis(elapsedNanos)),
                result.getAction());
        synchronized (lock) {
            samples.addLast(sample);
            while (samples.size() > capacity) {
                samples.removeFirst();
            }
            frameCount++;
            // The delegate is only knowable once a frame was actually scored;
            // scorerId() has already resolved by then (score() ran in this
            // request), so this is a cheap read, and only the first one costs
            // a lookup.
            if (scorer == null && result.getLivenessScore() != null) {
                scorer = passiveScorer.scorerId();
            }
        }
    }

    /** The panel payload; the empty disabled snapshot when the mode is off. */
    public DiagnosticsSnapshot snapshot() {
        if (!enabled) {
            return DiagnosticsSnapshot.disabled();
        }
        List<FrameSample> retained;
        String delegate;
        long total;
        synchronized (lock) {
            retained = List.copyOf(samples);
            delegate = scorer;
            total = frameCount;
        }
        Instant now = clock.instant();

        List<Double> scores = new ArrayList<>();
        for (FrameSample sample : retained) {
            if (sample.score() != null) {
                scores.add(sample.score());
            }
        }
        Double lastScore = scores.isEmpty() ? null : scores.get(scores.size() - 1);

        long frameCountInWindow = 0;
        Instant oldestInWindow = null;
        for (FrameSample sample : retained) {
            if (!sample.at().isBefore(now.minusMillis(FPS_WINDOW_MS))) {
                frameCountInWindow++;
                if (oldestInWindow == null) {
                    oldestInWindow = sample.at();
                }
            }
        }
        Double fps = null;
        if (frameCountInWindow >= 2 && oldestInWindow != null) {
            long spanMs = Math.max(1L, now.toEpochMilli() - oldestInWindow.toEpochMilli());
            fps = (frameCountInWindow - 1) * 1000.0 / spanMs;
        }

        Double avgMs = null;
        Double maxMs = null;
        if (!retained.isEmpty()) {
            long sum = 0L;
            long max = Long.MIN_VALUE;
            for (FrameSample sample : retained) {
                sum += sample.frameMs();
                max = Math.max(max, sample.frameMs());
            }
            avgMs = (double) sum / retained.size();
            maxMs = (double) max;
        }

        int from = Math.max(0, retained.size() - SNAPSHOT_ROWS);
        List<FrameSample> rows = List.copyOf(retained.subList(from, retained.size()));

        return new DiagnosticsSnapshot(true, delegate, fps, total,
                lastScore, median(scores), avgMs, maxMs, rows, now);
    }

    /** Median of the retained raw scores — same outlier-resistant shape the decision uses. */
    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
