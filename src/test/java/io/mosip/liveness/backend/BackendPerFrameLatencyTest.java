package io.mosip.liveness.backend;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.PadVerdict;

/**
 * Measured per-frame latency and throughput for the two backends executable
 * in this build — {@code mock} and {@code onnx-minifasnet-v2} — feeding both
 * the identical genuine-face fixture through the exact SPI conversation
 * {@code FaceLivenessEngine} runs per frame (analyzeFrame → assessPad →
 * scorePassiveLiveness), single-threaded, image decode excluded (the frame is
 * built once).
 *
 * <p>Two workloads are measured, because they answer different questions and
 * only one of them may be quoted as a per-frame cost:</p>
 * <ul>
 *   <li><b>{@code repeated-frame}</b> — the same frame object 100 times. This is
 *       a retry, a paused scene, or a benchmark that forgot to vary its input;
 *       since the backend memoizes one frame's analysis by pixel content, this
 *       path is now answered from that memo.</li>
 *   <li><b>{@code distinct-frames}</b> — 100 different frames (the fixture
 *       shifted and brightened by coprime steps), which is what a camera
 *       produces. Each frame still costs one detection and one inference.
 *       <b>Quote per-frame cost from this line.</b></li>
 * </ul>
 *
 * <p>Numbers land in {@code target/backend-latency-results.txt} and are
 * quoted in {@code docs/backend-interoperability-report.md} §10. Assertions
 * are deliberately sanity-level (every frame scored correctly, latency
 * positive, throughput above a floor) — timing varies by host, so this test
 * guards the <em>measurement</em>, not a wall-clock target.</p>
 */
class BackendPerFrameLatencyTest {

    private static final int WARMUP_FRAMES = 10;    // JIT + ORT session + cascade caches
    private static final int MEASURED_FRAMES = 100;
    private static final Path RESULTS = Path.of("target", "backend-latency-results.txt");

    @BeforeAll
    static void resetResultsFile() throws IOException {
        nu.pattern.OpenCV.loadLocally();
        Files.deleteIfExists(RESULTS);
    }

    @Test
    void mockBackendPerFrameLatencyAndThroughput() throws IOException {
        measure("mock", new MockLivenessBackend());
    }

    @Test
    void onnxBackendPerFrameLatencyAndThroughput() throws IOException {
        assumeTrue(OnnxMiniFasNetBackend.isRuntimeAvailable(),
                "ONNX Runtime not on the classpath — nothing to measure here");
        measure("onnx-minifasnet-v2", new OnnxMiniFasNetBackend());
    }

    // ------------------------------------------------------------ measurement

    private void measure(String backendId, LivenessBackend backend) throws IOException {
        backend.initialize(Map.of());
        try {
            Frame frame = genuineFaceFrame();
            Frame[] distinct = distinctFrames(WARMUP_FRAMES + MEASURED_FRAMES);

            // Correctness on representative input before any timing: the
            // numbers below must come from real decisions, not stubs.
            assertUsable(backendId, backend, frame);
            assertUsable(backendId, backend, distinct[0]);

            measureWorkload(backendId, backend, "repeated-frame",
                    repeat(frame, WARMUP_FRAMES), repeat(frame, MEASURED_FRAMES));
            measureWorkload(backendId, backend, "distinct-frames",
                    Arrays.copyOfRange(distinct, 0, WARMUP_FRAMES),
                    Arrays.copyOfRange(distinct, WARMUP_FRAMES, distinct.length));
        } finally {
            backend.shutdown();
        }
    }

    /** Non-null, in-range results for a frame before any number is taken from it. */
    private static void assertUsable(String backendId, LivenessBackend backend, Frame frame) {
        FaceSignals signals = backend.analyzeFrame(frame);
        assertNotNull(signals, backendId + ": analyzeFrame must never return null");
        double score = backend.scorePassiveLiveness(frame, signals);
        PadVerdict verdict = backend.assessPad(frame, signals);
        assertTrue(Double.isFinite(score) && score >= 0.0 && score <= 1.0,
                backendId + ": score must land in [0,1], was " + score);
        assertNotNull(verdict, backendId + ": PAD verdict must never be null");
        assertTrue(verdict.confidence() >= 0.0 && verdict.confidence() <= 1.0,
                backendId + ": PAD confidence out of [0,1], was " + verdict.confidence());
    }

    /**
     * Runs one workload through the engine's per-frame conversation and appends
     * its measurement line to {@link #RESULTS}.
     */
    private void measureWorkload(String backendId, LivenessBackend backend, String workload,
                                 Frame[] warmupFrames, Frame[] frames) throws IOException {
        for (Frame f : warmupFrames) {
            conversation(backend, f);
        }

        long[] analyzeNs = new long[frames.length];
        long[] padNs = new long[frames.length];
        long[] scoreNs = new long[frames.length];
        long[] totalNs = new long[frames.length];
        double minScore = Double.MAX_VALUE;
        double maxScore = 0.0;

        for (int i = 0; i < frames.length; i++) {
            long t0 = System.nanoTime();
            FaceSignals signals = backend.analyzeFrame(frames[i]);
            long t1 = System.nanoTime();
            PadVerdict verdict = backend.assessPad(frames[i], signals);
            long t2 = System.nanoTime();
            double score = backend.scorePassiveLiveness(frames[i], signals);
            long t3 = System.nanoTime();

            analyzeNs[i] = t1 - t0;
            padNs[i] = t2 - t1;
            scoreNs[i] = t3 - t2;
            totalNs[i] = t3 - t0;

            assertNotNull(verdict, backendId + ": PAD verdict must never be null");
            assertTrue(Double.isFinite(score) && score >= 0.0 && score <= 1.0,
                    backendId + ": score must land in [0,1], was " + score);
            minScore = Math.min(minScore, score);
            maxScore = Math.max(maxScore, score);
        }

        // ONNX must have done real work: the genuine face scores clearly
        // above zero (its own suite pins > 0.7). The mock is scripted.
        if ("onnx-minifasnet-v2".equals(backendId)) {
            assertTrue(minScore > 0.0,
                    "ONNX produced no real inference during measurement (min score " + minScore + ")");
        }

        String line = format(backendId, workload, frames.length, analyzeNs, padNs, scoreNs,
                totalNs, minScore, maxScore);
        Files.writeString(RESULTS, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println(line);

        // Sanity guards on the measurement itself — not a wall-clock target.
        assertTrue(mean(totalNs) > 0, backendId + ": latency must be positive");
        assertTrue(fps(totalNs) >= 1.0,
                backendId + "/" + workload + ": sustained throughput below 1 frame/s on "
                        + "the fixture — something is wrong beyond host variance");
        assertTrue(mean(totalNs) < 10_000_000_000L,
                backendId + ": mean per-frame latency above 10 s — a hang, not a slow host");
    }

    /** One frame through the engine's per-frame SPI conversation, in engine order. */
    private static void conversation(LivenessBackend backend, Frame frame) {
        FaceSignals signals = backend.analyzeFrame(frame);
        backend.assessPad(frame, signals);
        backend.scorePassiveLiveness(frame, signals);
    }

    private static String format(String backendId, String workload, int frames,
                                 long[] analyzeNs, long[] padNs,
                                 long[] scoreNs, long[] totalNs, double minScore, double maxScore) {
        double fps = fps(totalNs);
        return String.format(Locale.ROOT,
                "backend=%s workload=%s frames=%d warmup=%d thread=single decode=excluded | "
                        + "analyze mean=%.3f p50=%.3f p95=%.3f | "
                        + "pad mean=%.3f p50=%.3f p95=%.3f | "
                        + "score mean=%.3f p50=%.3f p95=%.3f | "
                        + "frame mean=%.3f p50=%.3f p95=%.3f max=%.3f ms | "
                        + "throughput=%.1f fps | score range=[%.4f, %.4f]",
                backendId, workload, frames, WARMUP_FRAMES,
                ms(mean(analyzeNs)), ms(p50(analyzeNs)), ms(p95(analyzeNs)),
                ms(mean(padNs)), ms(p50(padNs)), ms(p95(padNs)),
                ms(mean(scoreNs)), ms(p50(scoreNs)), ms(p95(scoreNs)),
                ms(mean(totalNs)), ms(p50(totalNs)), ms(p95(totalNs)), ms(max(totalNs)),
                fps, minScore, maxScore);
    }

    private static double mean(long[] nanos) {
        long sum = 0;
        for (long v : nanos) {
            sum += v;
        }
        return (double) sum / nanos.length;
    }

    private static double max(long[] nanos) {
        long m = 0;
        for (long v : nanos) {
            m = Math.max(m, v);
        }
        return m;
    }

    /** Nearest-rank percentile. */
    private static double p50(long[] nanos) {
        return percentile(nanos, 0.50);
    }

    private static double p95(long[] nanos) {
        return percentile(nanos, 0.95);
    }

    private static double percentile(long[] nanos, double p) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double fps(long[] totalNs) {
        double seconds = mean(totalNs) / 1_000_000_000.0;
        return seconds <= 0 ? 0 : 1.0 / seconds;
    }

    private static double ms(double nanos) {
        return nanos / 1_000_000.0;
    }

    /** {@code n} references to the same frame — the repeated-input workload. */
    private static Frame[] repeat(Frame frame, int n) {
        Frame[] frames = new Frame[n];
        Arrays.fill(frames, frame);
        return frames;
    }

    /**
     * {@code n} frames with different pixels, derived from the genuine fixture by
     * a per-channel offset of 0..4 counts (5&times;5&times;5 = 125 combinations,
     * so every frame in a 110-frame run is unique).
     *
     * <p>Deliberately mild: each frame still presents exactly one detectable
     * face, so the workload exercises the full detection + inference path rather
     * than the no-single-face short circuit. Nothing here may move the pixels
     * enough to change what the frame <em>is</em> — this workload measures cost,
     * not decisions.</p>
     */
    private static Frame[] distinctFrames(int n) {
        org.opencv.core.Mat bgr = Imgcodecs.imread("src/test/resources/fixtures/real-face.jpg");
        assertTrue(!bgr.empty(), "fixture image must load");
        assertTrue(n <= 125, "at most 125 unique perturbations are available");
        Frame[] frames = new Frame[n];
        org.opencv.core.Mat shifted = new org.opencv.core.Mat();
        try {
            for (int i = 0; i < n; i++) {
                org.opencv.core.Core.add(bgr, new org.opencv.core.Scalar(
                        i % 5, (i / 5) % 5, (i / 25) % 5), shifted);
                org.opencv.core.Mat rgb = new org.opencv.core.Mat();
                Imgproc.cvtColor(shifted, rgb, Imgproc.COLOR_BGR2RGB);
                byte[] data = new byte[rgb.rows() * rgb.cols() * rgb.channels()];
                rgb.get(0, 0, data);
                frames[i] = Frame.of(data, rgb.cols(), rgb.rows(), Frame.Format.RGB_888,
                        System.currentTimeMillis(), i);
                rgb.release();
            }
        } finally {
            shifted.release();
            bgr.release();
        }
        return frames;
    }

    private static Frame genuineFaceFrame() {
        org.opencv.core.Mat bgr = Imgcodecs.imread("src/test/resources/fixtures/real-face.jpg");
        assertTrue(!bgr.empty(), "fixture image must load");
        org.opencv.core.Mat rgb = new org.opencv.core.Mat();
        Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
        byte[] data = new byte[rgb.rows() * rgb.cols() * rgb.channels()];
        rgb.get(0, 0, data);
        return Frame.of(data, rgb.cols(), rgb.rows(), Frame.Format.RGB_888,
                System.currentTimeMillis(), 1);
    }
}
