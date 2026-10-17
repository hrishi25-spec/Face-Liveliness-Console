package io.mosip.liveness.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer timers for the per-frame decision path.
 *
 * <p>Every frame walks the same four phases — base64 decode, Haar face
 * detection, MiniFASNet scoring, and PAD — and until now there was no way to
 * see which one actually costs. These timers answer that directly: each phase
 * records to its own {@link Timer}, and {@link #snapshot()} reports count,
 * mean, max and <em>share of total</em> so the dominant phase is obvious.</p>
 *
 * <p>Static by design. {@link io.mosip.liveness.services.ImageUtils} and the
 * scoring services are instantiated directly in unit tests
 * ({@code new ImageUtils()}, {@code new PassiveScoringService(...)}) as well as
 * by Spring, so constructor injection would have meant threading a registry
 * through every test for no benefit. A static holder instruments both, and
 * means the non-Spring entry points (the eval harness, the corpus tools) are
 * measured too.</p>
 *
 * <p>Counters live in the JVM, so they reset on restart — same caveat as the
 * rate-limiter tallies in {@code /api/v1/metrics}.</p>
 */
public final class PipelineTimers {

    /** base64 → Mat. Includes the decode itself and the size/dimension guards. */
    public static final String DECODE = "decode";
    /** Haar cascade face detection, which also runs the eye pass. */
    public static final String FACE_DETECT = "facedetect";
    /** MiniFASNet ONNX inference for the passive liveness score. */
    public static final String ONNX_SCORE = "onnxscore";
    /** The OpenCV sharpness/brightness fallback, when the model is not loaded. */
    public static final String HEURISTIC_SCORE = "heuristicscore";
    /** PAD's FFT + texture heuristics. */
    public static final String PAD_HEURISTIC = "padheuristic";
    /** PAD's opinion from the ONNX model. */
    public static final String PAD_ONNX = "paddonx";
    /** Whole request handler, decode through verdict. */
    public static final String TOTAL = "total";

    /** Report order, and the order phases appear in the metrics payload. */
    private static final String[] PHASES = {
            DECODE, FACE_DETECT, ONNX_SCORE, HEURISTIC_SCORE, PAD_HEURISTIC, PAD_ONNX, TOTAL
    };

    private static final String PREFIX = "pad.pipeline.";

    private static volatile MeterRegistry registry = new SimpleMeterRegistry();

    private PipelineTimers() {
    }

    /**
     * Swaps in the registry Spring should use. Called once from
     * {@code AppConfig}; if it is never called the default
     * {@link SimpleMeterRegistry} keeps working, so unit tests that touch this
     * without a context do not NPE.
     */
    public static void use(MeterRegistry replacement) {
        if (replacement != null) {
            registry = replacement;
        }
    }

    public static MeterRegistry registry() {
        return registry;
    }

    /** Start a sample. Pair with {@link #stop(Timer.Sample, String)}. */
    public static Timer.Sample start() {
        return Timer.start(registry);
    }

    public static void stop(Timer.Sample sample, String phase) {
        if (sample != null) {
            // Timer.Sample.stop(Timer) stops the sample AND records it, on the
            // clock the sample was started with.
            sample.stop(timer(phase));
        }
    }

    public static void timed(String phase, Runnable body) {
        Timer.Sample sample = start();
        try {
            body.run();
        } finally {
            stop(sample, phase);
        }
    }

    public static <T> T timed(String phase, Supplier<T> body) {
        Timer.Sample sample = start();
        try {
            return body.get();
        } finally {
            stop(sample, phase);
        }
    }

    private static Timer timer(String phase) {
        return Timer.builder(PREFIX + phase)
                .description("Per-frame decision-path phase: " + phase)
                // Percentiles matter here: the mean hides the slow tail, and the
                // tail is what a registration client actually feels.
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    /**
     * Per-phase breakdown with each phase's share of the measured total, in
     * milliseconds. Phases with no samples are omitted so the payload says
     * "never ran" rather than "ran zero times".
     */
    public static Map<String, PhaseTiming> snapshot() {
        Map<String, Timer> measured = new LinkedHashMap<>();
        double totalMs = 0.0;
        for (String phase : PHASES) {
            // find() returns a Search whose timer() is null until the meter has
            // actually recorded something — timers register lazily on first
            // use, so "never ran" is null, not a zero-count timer.
            Timer timer = registry.find(PREFIX + phase).timer();
            if (timer != null && timer.count() > 0) {
                measured.put(phase, timer);
                if (!TOTAL.equals(phase)) {
                    totalMs += ms(timer.totalTime(TimeUnit.NANOSECONDS));
                }
            }
        }

        Map<String, PhaseTiming> out = new LinkedHashMap<>();
        for (Map.Entry<String, Timer> entry : measured.entrySet()) {
            Timer timer = entry.getValue();
            double sum = ms(timer.totalTime(TimeUnit.NANOSECONDS));
            // Share is against the sum of the phases, not the wall-clock total:
            // the phases overlap (face detection feeds scoring) and the request
            // total includes JSON, JPA and audit work, so dividing by wall-clock
            // time would under-report every phase.
            double share = totalMs <= 0.0 ? 0.0 : round2(sum * 100.0 / totalMs);
            out.put(entry.getKey(), new PhaseTiming(
                        timer.count(),
                        round3(sum),
                        round3(timer.mean(TimeUnit.NANOSECONDS) / 1_000_000.0),
                        round3(timer.max(TimeUnit.NANOSECONDS) / 1_000_000.0),
                        round3(percentile(timer, 0.99)),
                        // TOTAL is wall clock for the whole request, so it
                        // includes JSON, JPA and audit work that is not a
                        // pipeline phase. Giving it a share of the phase sum
                        // produced a nonsense figure well above 100%; it is
                        // reported as 0 and read as "not a phase".
                        TOTAL.equals(entry.getKey()) ? 0.0 : share));
        }
        return out;
    }

    /** Drops every recorded sample. Test seam. */
    public static void reset() {
        for (String phase : PHASES) {
            Timer timer = registry.find(PREFIX + phase).timer();
            if (timer != null) {
                registry.remove(timer.getId());
            }
        }
    }

    /** p-quantile in milliseconds, 0.0 when the timer has no percentiles. */
    private static double percentile(Timer timer, double q) {
        for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
            if (value.percentile() == q) {
                return value.value(TimeUnit.NANOSECONDS) / 1_000_000.0;
            }
        }
        return 0.0;
    }

    private static double ms(double nanos) {
        return nanos / 1_000_000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * One phase's numbers. {@code sharePct} is the phase's share of all
     * measured phases, so the phases add up to ~100 and can be compared
     * directly.
     */
    public record PhaseTiming(long count, double totalMs, double meanMs, double maxMs,
                              double p99Ms, double sharePct) {
    }
}