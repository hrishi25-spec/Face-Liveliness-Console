package io.mosip.liveness.backend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.engine.AssessmentStatus;
import io.mosip.liveness.engine.FaceLivenessEngine;
import io.mosip.liveness.engine.FrameAssessment;
import io.mosip.liveness.testing.MutableClock;

import java.util.function.Supplier;

/**
 * The {@link LivenessBackend} SPI interoperability contract, executed
 * identically against the three backends compared in
 * {@code docs/backend-interoperability-report.md}: {@code mock}, {@code onnx}
 * and {@code mediapipe}.
 *
 * <p>Interoperability here means the engine cannot tell the backends apart at
 * the seam: each must expose an audit-safe {@code id()}, speak the full
 * conversation (initialize → analyzeFrame → score → PAD → shutdown) or refuse
 * to start <b>fail-closed</b> as a {@link LivenessException} — never a raw
 * reflection/class-loading error — and {@link FaceLivenessEngine} must accept
 * an available backend and reject an unavailable one through the same
 * constructor path with the same error code.
 *
 * <p>The contract is environment-independent: whether a backend is available
 * (e.g. MediaPipe without the TFLite runtime on the classpath) is probed, not
 * assumed, and the assertions that follow the probe hold either way.
 */
class LivenessBackendInteroperabilityTest {

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    /** One backend under comparison; {@code toString} labels the parameterized runs. */
    record Case(String name, Supplier<LivenessBackend> factory) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final Case MOCK = new Case("mock", MockLivenessBackend::new);
    private static final Case ONNX = new Case("onnx", OnnxMiniFasNetBackend::new);
    private static final Case MEDIAPIPE = new Case("mediapipe", MediaPipeFaceMeshBackend::new);

    static Stream<Case> backends() {
        return Stream.of(MOCK, ONNX, MEDIAPIPE);
    }

    // ------------------------------------------------------------ identity

    @ParameterizedTest(name = "{0}: id is stable and audit-safe")
    @MethodSource("backends")
    void idIsStableAndAuditSafe(Case c) {
        LivenessBackend backend = c.factory().get();
        String id = backend.id();
        assertNotNull(id, c.name() + ": id() must never be null");
        assertFalse(id.isBlank(), c.name() + ": id() must not be blank");
        assertTrue(id.matches("[a-z0-9]+(-[a-z0-9]+)*"),
                c.name() + ": id travels into audit records — lowercase/hyphen vocabulary only, was: " + id);
        assertEquals(id, backend.id(), c.name() + ": id() must be stable across calls");
    }

    @Test
    void theThreeBackendsReportDistinctIds() {
        Set<String> ids = backends()
                .map(c -> c.factory().get().id())
                .collect(Collectors.toSet());
        assertEquals(3, ids.size(), "audit records must disambiguate the backends: " + ids);
    }

    // ------------------------------------------------------------ lifecycle + conversation

    @ParameterizedTest(name = "{0}: clean init or fail-closed refusal")
    @MethodSource("backends")
    void initializationIsCleanOrFailClosed(Case c) {
        LivenessBackend backend = c.factory().get();
        try {
            backend.initialize(Map.of());
        } catch (LivenessException e) {
            // The only legal refusal: a coded, actionable fail-closed error
            // (e.g. MediaPipe without org.tensorflow:tensorflow-lite).
            assertNotNull(e.errorCode(), c.name() + ": refusal must carry an error code");
            assertTrue(e.getMessage() != null && !e.getMessage().isBlank(),
                    c.name() + ": refusal must say what to fix, was: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            fail(c.name() + ": initialize leaked " + e.getClass().getName()
                    + " — the contract allows only a clean start or LivenessException: " + e);
        }

        // Available → the full SPI conversation must hold.
        try {
            Frame frame = noiseFrame();
            FaceSignals signals = backend.analyzeFrame(frame);
            assertNotNull(signals, c.name() + ": analyzeFrame must never return null");
            assertTrue(signals.faceCount() >= 0,
                    c.name() + ": faceCount must be a plain detection result, was " + signals.faceCount());

            double score = backend.scorePassiveLiveness(frame, signals);
            assertTrue(score >= 0.0 && score <= 1.0,
                    c.name() + ": passive score must stay in [0,1], was " + score);

            PadVerdict verdict = backend.assessPad(frame, signals);
            assertNotNull(verdict, c.name() + ": PAD verdict must never be null (fail closed is a verdict)");
            assertTrue(verdict.confidence() >= 0.0 && verdict.confidence() <= 1.0,
                    c.name() + ": PAD confidence out of [0,1], was " + verdict.confidence());

            // The engine gates on faceCount itself; a no-face signal must not
            // fabricate an attack — an unearned PAD block fails users closed.
            PadVerdict noFace = backend.assessPad(frame, FaceSignals.noFace(0.0));
            assertNotNull(noFace, c.name() + ": no-face PAD verdict must not be null");
            assertFalse(noFace.attackDetected(),
                    c.name() + ": no-face frames must not fabricate a PAD attack");

            backend.shutdown();
            backend.shutdown(); // idempotent teardown
        } finally {
            backend.shutdown();
        }
    }

    // ------------------------------------------------------------ engine seam

    @ParameterizedTest(name = "{0}: engine accepts iff available")
    @MethodSource("backends")
    void engineHonoursTheSameAvailabilityContract(Case c) {
        boolean available;
        LivenessBackend probe = c.factory().get();
        try {
            probe.initialize(Map.of());
            probe.shutdown();
            available = true;
        } catch (LivenessException refused) {
            available = false;
        }

        if (available) {
            assertDoesNotThrow(
                    () -> new FaceLivenessEngine(LivenessConfig.builder().build(), c.factory().get()),
                    c.name() + ": an available backend must construct an engine cleanly");
            return;
        }
        // Unavailable → the engine must fail closed with its own mapped code,
        // so a missing runtime degrades to a device error, not a crash loop.
        LivenessException ex = assertThrows(LivenessException.class,
                () -> new FaceLivenessEngine(LivenessConfig.builder().build(), c.factory().get()),
                c.name() + ": an unavailable backend must fail engine construction closed");
        assertEquals(LivenessErrorCode.DEVICE_CONNECTION_FAILURE, ex.errorCode(),
                c.name() + ": the engine's uniform mapping for a backend that will not start");
    }

    // ------------------------------------------------------------ ONNX through the engine

    @Test
    void onnxBackendDrivesTheEnginePipelineOnTheGenuineFaceFixture() {
        // The suite proves ONNX scoring directly (OnnxMiniFasNetBackendTest)
        // and the engine with the mock (engine tests); this closes the pair —
        // the engine's own per-frame pipeline with the real ONNX backend.
        FaceLivenessEngine engine = new FaceLivenessEngine(
                LivenessConfig.builder().passiveMinFrames(2).build(),
                new OnnxMiniFasNetBackend(),
                AuditLogger.noop(), new MetricsCollector(), new MutableClock(1));
        String sid = engine.initSession(WorkflowType.RESIDENT_REGISTRATION);
        Frame face = genuineFaceFrame();

        FrameAssessment first = engine.pushFrame(sid, face);
        assertNotNull(first.status(), "first frame must yield a well-formed status");
        assertTrue(Double.isNaN(first.livenessScore())
                        || (first.livenessScore() >= 0.0 && first.livenessScore() <= 1.0),
                "score must be NaN (rejected early) or in [0,1], was " + first.livenessScore());

        FrameAssessment second = engine.pushFrame(sid, face);
        assertTrue(Set.of(AssessmentStatus.PASSED, AssessmentStatus.ESCALATED_TO_ACTIVE,
                        AssessmentStatus.RETRYABLE_ERROR, AssessmentStatus.SCORING)
                        .contains(second.status()),
                "a decided-or-gated frame, never an engine fault: " + second.status());
    }

    @Test
    void theBackendSelectionConfigBuildsTheEngineThroughTheSameContract() {
        // Report recommendation on F5: construct the engine through the
        // selection path, not just by handing the backend over directly.
        for (String id : new String[]{"mock", "onnx-minifasnet-v2", "mediapipe-facemesh"}) {
            assertEquals(id, LivenessBackendSelection.parse(id).createBackend().id());

            boolean available;
            LivenessBackend probe = LivenessBackendSelection.parse(id).createBackend();
            try {
                probe.initialize(Map.of());
                probe.shutdown();
                available = true;
            } catch (LivenessException refused) {
                available = false;
            }

            if (available) {
                assertDoesNotThrow(
                        () -> new FaceLivenessEngine(LivenessConfig.builder().build(),
                                LivenessBackendSelection.parse(id).createBackend()),
                        id + ": a selectable backend must construct an engine cleanly");
            } else {
                LivenessException ex = assertThrows(LivenessException.class,
                        () -> new FaceLivenessEngine(LivenessConfig.builder().build(),
                                LivenessBackendSelection.parse(id).createBackend()),
                        id + ": an unavailable selection must fail engine construction closed");
                assertEquals(LivenessErrorCode.DEVICE_CONNECTION_FAILURE, ex.errorCode(),
                        id + ": the engine's uniform mapping, reached via the selection path");
            }
        }
        // The key's default keeps this path on the scripted mock (F5 unchanged).
        assertEquals("mock", LivenessBackendSelection.parse("auto").createBackend().id());
        assertEquals("mock", LivenessBackendSelection.parse("heuristic").createBackend().id());
    }

    @Test
    void theReportCitesExactlyTheseBackendIds() {
        // docs/backend-interoperability-report.md names these ids verbatim;
        // a drift here is a report update, not a silent rename.
        assertEquals("mock", MOCK.factory().get().id());
        assertEquals("onnx-minifasnet-v2", ONNX.factory().get().id());
        assertEquals("mediapipe-facemesh", MEDIAPIPE.factory().get().id());
    }

    // ------------------------------------------------------------ frames

    private static Frame noiseFrame() {
        byte[] data = new byte[96 * 96 * 3];
        new Random(42).nextBytes(data);
        return Frame.of(data, 96, 96, Frame.Format.RGB_888, System.currentTimeMillis(), 1);
    }

    private static Frame genuineFaceFrame() {
        org.opencv.core.Mat bgr = Imgcodecs.imread(
                "src/test/resources/fixtures/real-face.jpg");
        assertTrue(!bgr.empty(), "fixture image must load");
        org.opencv.core.Mat rgb = new org.opencv.core.Mat();
        Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
        byte[] data = new byte[rgb.rows() * rgb.cols() * rgb.channels()];
        rgb.get(0, 0, data);
        return Frame.of(data, rgb.cols(), rgb.rows(), Frame.Format.RGB_888,
                System.currentTimeMillis(), 1);
    }
}
