package io.mosip.liveness.backend;

import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.core.ProbabilityCalibration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled MiniFASNet ONNX backend: provenance (checksum), load/unload,
 * face-count gating and a full inference smoke test.
 */
class OnnxMiniFasNetBackendTest {

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Frame noiseFrame() {
        byte[] data = new byte[96 * 96 * 3];
        new Random(42).nextBytes(data);
        return Frame.of(data, 96, 96, Frame.Format.RGB_888, System.currentTimeMillis(), 1);
    }

    @Test
    void bundledModelMatchesItsPublishedSha256() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(OnnxMiniFasNetBackend.DEFAULT_MODEL_RESOURCE)) {
            assertNotNull(in, "bundled model must exist on the classpath");
            byte[] model = in.readAllBytes();
            assertEquals(1_744_116, model.length, "size published in the model card");
            assertEquals(OnnxMiniFasNetBackend.DEFAULT_MODEL_SHA256,
                    OnnxMiniFasNetBackend.sha256(model));
        }
    }

    @Test
    void bundledModelInitializesAndReportsReady() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        assertTrue(OnnxMiniFasNetBackend.isRuntimeAvailable(), "ONNX Runtime must be on the classpath");
        backend.initialize(Map.of());
        try {
            assertTrue(backend.isReady());
            assertEquals("onnx-minifasnet-v2", backend.id());
        } finally {
            backend.shutdown();
        }
        assertTrue(!backend.isReady(), "shutdown must clear readiness");
    }

    @Test
    void missingModelPathFailsWithModelIntegrity() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        LivenessException ex = assertThrows(LivenessException.class,
                () -> backend.initialize(Map.of(
                        OnnxMiniFasNetBackend.OPTION_MODEL_PATH, "/nonexistent/model.onnx")));
        assertEquals(LivenessErrorCode.MODEL_INTEGRITY, ex.errorCode());
    }

    @Test
    void invalidModelSourceFailsWithModelIntegrity() throws Exception {
        // A file that is definitely not the published model.
        java.nio.file.Path bogus = java.nio.file.Files.createTempFile("bogus", ".onnx");
        java.nio.file.Files.writeString(bogus, "not a model at all");
        try {
            OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
            LivenessException ex = assertThrows(LivenessException.class, () -> backend.initialize(Map.of(
                    OnnxMiniFasNetBackend.OPTION_MODEL_RESOURCE, bogus.toString(),
                    OnnxMiniFasNetBackend.OPTION_EXPECTED_SHA256,
                    OnnxMiniFasNetBackend.DEFAULT_MODEL_SHA256)));
            assertEquals(LivenessErrorCode.MODEL_INTEGRITY, ex.errorCode());
        } finally {
            java.nio.file.Files.deleteIfExists(bogus);
        }
    }

    @Test
    void softmaxIsNormalizedAndOrderPreserving() {
        double[] p = OnnxMiniFasNetBackend.softmax(new float[]{1.0f, 3.0f, -2.0f});
        double sum = p[0] + p[1] + p[2];
        assertEquals(1.0, sum, 1e-6);
        assertTrue(p[1] > p[0] && p[0] > p[2]);
    }

    @Test
    void faceCountGatesBothScoreAndPad() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            Frame frame = noiseFrame();

            // No face (per the caller's signals) -> no score, no fabricated attack.
            assertEquals(0.0, backend.scorePassiveLiveness(frame, FaceSignals.noFace(0.0)), 1e-12);
            PadVerdict verdict = backend.assessPad(frame, FaceSignals.noFace(0.0));
            assertTrue(!verdict.attackDetected());

            // A frame the detector cannot analyse yields a neutral score in [0,1].
            double score = backend.scorePassiveLiveness(frame,
                    new FaceSignals(1, 0.8, null, null, null, null, null, null, null));
            assertTrue(score >= 0.0 && score <= 1.0, "score out of range: " + score);
        } finally {
            backend.shutdown();
        }
    }

    @Test
    void packNchwKeepsRawByteRangeAndBgrPlanes() {
        // One pixel: B=10, G=128, R=255. The model expects raw 0..255, so the
        // packed floats must be exactly those values (a /255 regression would
        // make these ~0.04/0.5/1.0 and silently break every score).
        float[] nchw = OnnxMiniFasNetBackend.packNchw(new byte[]{10, (byte) 128, (byte) 255}, 1);
        assertEquals(10.0f, nchw[0], 1e-6f, "B plane");
        assertEquals(128.0f, nchw[1], 1e-6f, "G plane");
        assertEquals(255.0f, nchw[2], 1e-6f, "R plane");
    }

    @Test
    void cropRectStaysInsideTheFrameAndKeepsTheMargin() {
        // Centred face with room to spare -> the full 2.7x margin, no clamping.
        Rect centred = OnnxMiniFasNetBackend.cropRect(new Rect(220, 160, 100, 100), 640, 480);
        assertEquals(270, centred.width);
        assertEquals(270, centred.height);

        // Large face on the top edge -> clamped into the frame rather than
        // black-padded (the device-specific SCREEN_REPLAY false-positive cause).
        Rect edge = OnnxMiniFasNetBackend.cropRect(new Rect(180, 0, 300, 300), 640, 480);
        assertTrue(edge.x >= 0 && edge.y >= 0, "crop origin must be inside the frame");
        assertTrue(edge.x + edge.width <= 640, "crop must fit the frame width");
        assertTrue(edge.y + edge.height <= 480, "crop must fit the frame height");
        assertTrue(edge.width >= 300, "crop must not be tighter than the face");
    }

    @Test
    void genuineFaceScoresAsLive() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            Mat bgr = Imgcodecs.imread(
                    "src/test/resources/fixtures/real-face.jpg");
            assertTrue(!bgr.empty(), "fixture image must load");
            Mat rgb = new Mat();
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            byte[] data = new byte[rgb.rows() * rgb.cols() * rgb.channels()];
            rgb.get(0, 0, data);
            Frame frame = Frame.of(data, rgb.cols(), rgb.rows(), Frame.Format.RGB_888,
                    System.currentTimeMillis(), 0);

            FaceSignals signals = backend.analyzeFrame(frame);
            assertEquals(1, signals.faceCount(), "the fixture must contain a detectable face");

            double score = backend.scorePassiveLiveness(frame, signals);
            assertTrue(score > 0.7,
                    "a genuine, well-lit face must score well above chance, was " + score);
            PadVerdict verdict = backend.assessPad(frame, signals);
            assertTrue(!verdict.attackDetected(),
                    "a genuine face must not be flagged as an attack: " + verdict);
        } finally {
            backend.shutdown();
        }
    }

    @Test
    void analyzeFrameReportsDetectedFaceCount() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            FaceSignals signals = backend.analyzeFrame(noiseFrame());
            assertNotNull(signals);
            assertTrue(signals.faceCount() >= 0, "face count must be a plain detection result");
        } finally {
            backend.shutdown();
        }
    }

    /** The committed genuine face as an RGB_888 {@link Frame}. */
    private static Frame realFaceFrame() {
        Mat bgr = Imgcodecs.imread("src/test/resources/fixtures/real-face.jpg");
        assertTrue(!bgr.empty(), "fixture image must load");
        Mat rgb = new Mat();
        Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
        byte[] data = new byte[rgb.rows() * rgb.cols() * rgb.channels()];
        rgb.get(0, 0, data);
        return Frame.of(data, rgb.cols(), rgb.rows(), Frame.Format.RGB_888,
                System.currentTimeMillis(), 0);
    }

    @Test
    void temperatureDeSaturatesTheGenuineScoreButKeepsItAboveTheThreshold() {
        OnnxMiniFasNetBackend raw = new OnnxMiniFasNetBackend();
        raw.initialize(Map.of(OnnxMiniFasNetBackend.OPTION_TEMPERATURE, "1"));
        double rawScore;
        try {
            Frame frame = realFaceFrame();
            rawScore = raw.scorePassiveLiveness(frame, raw.analyzeFrame(frame));
        } finally {
            raw.shutdown();
        }

        OnnxMiniFasNetBackend calibrated = new OnnxMiniFasNetBackend();
        calibrated.initialize(Map.of(OnnxMiniFasNetBackend.OPTION_TEMPERATURE, "4.0"));
        double calibratedScore;
        try {
            Frame frame = realFaceFrame();
            calibratedScore = calibrated.scorePassiveLiveness(frame, calibrated.analyzeFrame(frame));
        } finally {
            calibrated.shutdown();
        }

        assertTrue(rawScore > 0.98, "the raw model is expected to be over-confident, was " + rawScore);
        assertTrue(calibratedScore > ProbabilityCalibration.DEFAULT_PIVOT,
                "a genuine face must still clear the default threshold, was " + calibratedScore);
        assertTrue(calibratedScore < rawScore - 0.03,
                "calibration must pull the saturated top down: raw=" + rawScore
                        + " calibrated=" + calibratedScore);
    }

    @Test
    void invalidTemperatureIsAConfigurationError() {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        assertThrows(LivenessException.class,
                () -> backend.initialize(Map.of(OnnxMiniFasNetBackend.OPTION_TEMPERATURE, "0.5")));
        assertThrows(LivenessException.class,
                () -> backend.initialize(Map.of(OnnxMiniFasNetBackend.OPTION_TEMPERATURE, "hot")));
    }

    // ------------------------------------------------------------------
    // ONNX intra-op threads: a cost knob, never a decision input
    // ------------------------------------------------------------------

    /** The three SPI answers for one frame, as an exact hex fingerprint. */
    private static String conversationValues(Map<String, String> options, Frame frame) {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(options);
        try {
            FaceSignals signals = backend.analyzeFrame(frame);
            PadVerdict pad = backend.assessPad(frame, signals);
            return Double.toHexString(signals.qualityScore())
                    + "|" + Double.toHexString(backend.scorePassiveLiveness(frame, signals))
                    + "|" + pad.attackDetected() + "/" + pad.attackType()
                    + "/" + Double.toHexString(pad.confidence());
        } finally {
            backend.shutdown();
        }
    }

    /**
     * Configuring the intra-op thread count must not move a single bit: one
     * thread, the derived default and a wider setting answer identically on both
     * fixtures. The knob exists because fewer threads measured <em>faster</em> on
     * a shared CPU (see the performance baseline), not because it is a model
     * parameter — and a score that shifted with it would be a decision change.
     */
    @Test
    void intraOpThreadCountDoesNotMoveAnyValue() throws Exception {
        Frame genuine = fixtureFrame("real-face.jpg");
        Frame attack = fixtureFrame("print-attack.jpg");

        String genuineDefault = conversationValues(Map.of(), genuine);
        String attackDefault = conversationValues(Map.of(), attack);
        assertEquals(genuineDefault, conversationValues(
                        Map.of(OnnxMiniFasNetBackend.OPTION_INTRA_OP_THREADS, "1"), genuine),
                "one intra-op thread must not move the genuine face's values");
        assertEquals(attackDefault, conversationValues(
                        Map.of(OnnxMiniFasNetBackend.OPTION_INTRA_OP_THREADS, "1"), attack),
                "one intra-op thread must not move the attack fixture's values");
        assertEquals(genuineDefault, conversationValues(
                        Map.of(OnnxMiniFasNetBackend.OPTION_INTRA_OP_THREADS, "4"), genuine),
                "four intra-op threads must not move the genuine face's values");
        assertEquals(attackDefault, conversationValues(
                        Map.of(OnnxMiniFasNetBackend.OPTION_INTRA_OP_THREADS, "4"), attack),
                "four intra-op threads must not move the attack fixture's values");
    }

    @Test
    void intraOpThreadsDefaultToTheCoreDerivationAndRejectNonPositiveValues() {
        int cores = Runtime.getRuntime().availableProcessors();
        assertEquals(Math.max(1, cores / 2), OnnxMiniFasNetBackend.intraOpThreads(Map.of()));
        if (cores <= 2) {
            // The target hardware: one inference thread, so the other core stays
            // free for decode/detection of the next frame.
            assertEquals(1, OnnxMiniFasNetBackend.intraOpThreads(Map.of()),
                    "the 2-core target must run one intra-op thread");
        }
        assertEquals(3, OnnxMiniFasNetBackend.intraOpThreads(
                Map.of(OnnxMiniFasNetBackend.OPTION_INTRA_OP_THREADS, " 3 ")),
                "an explicit value is honoured, surrounding blanks trimmed");
        assertThrows(LivenessException.class, () -> OnnxMiniFasNetBackend.intraOpThreads(
                Map.of(OnnxMiniFasNetBackend.OPTION_INTRA_OP_THREADS, "0")),
                "0 hands the choice back to ORT and must be refused, not absorbed");
        assertThrows(LivenessException.class, () -> OnnxMiniFasNetBackend.intraOpThreads(
                Map.of(OnnxMiniFasNetBackend.OPTION_INTRA_OP_THREADS, "many")));
    }

    // ------------------------------------------------------------------
    // Per-frame reuse: the memo must change the cost, never a value
    // ------------------------------------------------------------------

    /** A committed fixture as an RGB_888 {@link Frame}, straight from the classpath. */
    private static Frame fixtureFrame(String name) throws Exception {
        try (InputStream in = OnnxMiniFasNetBackendTest.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            assertNotNull(in, "fixture " + name + " must be on the test classpath");
            MatOfByte buf = new MatOfByte(in.readAllBytes());
            Mat bgr = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR);
            buf.release();
            assertTrue(!bgr.empty(), "fixture " + name + " must decode as an image");
            Mat rgb = new Mat();
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            int width = rgb.cols();
            int height = rgb.rows();
            byte[] data = new byte[height * width * rgb.channels()];
            rgb.get(0, 0, data);
            bgr.release();
            rgb.release();
            return Frame.of(data, width, height, Frame.Format.RGB_888, System.currentTimeMillis(), 1);
        }
    }

    /**
     * The memo must not move a bit, but the model's own output is not bit-stable
     * across CPUs: ONNX Runtime selects float32 kernels per generation, and the
     * same fixture lands a few ulps apart on a different runner. The pre-memo
     * reference values are therefore compared with a 1 ppm tolerance, while two
     * calls inside this test — served from the same memo — are compared exactly.
     * The tolerance is still far tighter than a wrong crop, a rescaled input or
     * a re-ordered softmax would produce, and the exact pair catches a memo that
     * recomputes instead of reusing.
     */
    @Test
    void memoizedAnalysisReproducesThePreMemoValues() throws Exception {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            Frame genuine = fixtureFrame("real-face.jpg");
            FaceSignals genuineSignals = backend.analyzeFrame(genuine);
            assertEquals(1, genuineSignals.faceCount(), "the fixture must contain one detectable face");
            assertEquals("0x1.db82b901dc375p-1", Double.toHexString(genuineSignals.qualityScore()),
                    "genuine face quality drifted");
            double genuineScore = backend.scorePassiveLiveness(genuine, genuineSignals);
            assertEquals(0x1.fd6f64eda63ap-1, genuineScore, 1e-6,
                    "genuine live probability drifted");
            assertEquals(Double.toHexString(genuineScore),
                    Double.toHexString(backend.scorePassiveLiveness(genuine, genuineSignals)),
                    "a repeated score must be served bit-for-bit from the memo");
            PadVerdict genuinePad = backend.assessPad(genuine, genuineSignals);
            assertTrue(!genuinePad.attackDetected(), "a genuine face must not become an attack");
            assertEquals(0x1.fd6f64eda63ap-1, genuinePad.confidence(), 1e-6,
                    "genuine PAD confidence drifted");

            Frame printAttack = fixtureFrame("print-attack.jpg");
            FaceSignals attackSignals = backend.analyzeFrame(printAttack);
            assertEquals(1, attackSignals.faceCount(), "the attack fixture keeps one detectable face");
            assertEquals("0x1.c1dd992d3df9ep-2", Double.toHexString(attackSignals.qualityScore()),
                    "attack face quality drifted");
            double attackScore = backend.scorePassiveLiveness(printAttack, attackSignals);
            assertEquals(0x1.1fbc56520293cp-11, attackScore, 1e-9,
                    "print-attack live probability drifted");
            assertEquals(Double.toHexString(attackScore),
                    Double.toHexString(backend.scorePassiveLiveness(printAttack, attackSignals)),
                    "a repeated attack score must be served bit-for-bit from the memo");
            PadVerdict attackPad = backend.assessPad(printAttack, attackSignals);
            assertTrue(attackPad.attackDetected(), "the print attack must still be refused");
            assertEquals(PadAttackType.SCREEN_REPLAY, attackPad.attackType());
            assertEquals(0x1.ffb70c86ca4cap-1, attackPad.confidence(), 1e-6,
                    "print-attack PAD confidence drifted");
        } finally {
            backend.shutdown();
        }
    }

    /**
     * One frame through the engine's per-frame conversation costs one detection
     * and one inference — it used to cost three detections (analyzeFrame plus a
     * detection inside each probability pass) and two inferences.
     */
    @Test
    void oneFrameThroughTheSpiConversationCostsOneDetectionAndOneInference() throws Exception {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            Frame frame = fixtureFrame("real-face.jpg");
            FaceSignals signals = backend.analyzeFrame(frame);
            backend.scorePassiveLiveness(frame, signals);
            backend.assessPad(frame, signals);
            assertEquals(1, backend.detections(), "one frame must be detected once");
            assertEquals(1, backend.inferences(), "one frame must be inferred once");

            // A frame that is only analysed must not pay for an inference it
            // never asked for, and different pixels must not reuse the entry.
            backend.analyzeFrame(noiseFrame());
            assertEquals(2, backend.detections(), "a new frame must be detected");
            assertEquals(1, backend.inferences(), "analyzeFrame alone must not infer");

            // shutdown() drops the memo, so a reload can never serve the old
            // model's numbers back to a caller.
            backend.shutdown();
            backend.initialize(Map.of());
            backend.analyzeFrame(frame);
            assertEquals(3, backend.detections(), "a reload must invalidate the memo");
        } finally {
            backend.shutdown();
        }
    }

    /**
     * The HTTP path rebuilds the frame for every backend call (fresh object,
     * fresh timestamp, identical pixels), so the memo must key on content: the
     * rebuilt frame has to reuse the analysis, and changed pixels must not.
     */
    @Test
    void memoKeysOnPixelContentNotOnTheFrameObject() throws Exception {
        OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
        backend.initialize(Map.of());
        try {
            Frame first = fixtureFrame("real-face.jpg");
            double firstScore = backend.scorePassiveLiveness(first, backend.analyzeFrame(first));
            int detections = backend.detections();
            int inferences = backend.inferences();

            Frame rebuilt = fixtureFrame("real-face.jpg");   // same pixels, new object and timestamp
            double rebuiltScore = backend.scorePassiveLiveness(rebuilt, backend.analyzeFrame(rebuilt));
            assertEquals(Double.toHexString(firstScore), Double.toHexString(rebuiltScore),
                    "a rebuilt frame must produce the identical score");
            assertEquals(detections, backend.detections(), "a rebuilt frame must reuse the detection");
            assertEquals(inferences, backend.inferences(), "a rebuilt frame must reuse the inference");

            Frame changed = fixtureFrame("print-attack.jpg");
            backend.analyzeFrame(changed);
            assertEquals(detections + 1, backend.detections(), "different pixels must be detected afresh");
        } finally {
            backend.shutdown();
        }
    }
}
