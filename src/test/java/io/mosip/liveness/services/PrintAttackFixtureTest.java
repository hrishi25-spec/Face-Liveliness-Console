package io.mosip.liveness.services;

import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Contract of the committed {@code fixtures/print-attack.jpg}: a degraded
 * (printed/low-texture) presentation of the real-face fixture that keeps
 * exactly one Haar-detectable face while being classified as an attack —
 * by the texture heuristic (PRINTED_PHOTO) and by the MiniFASNet model as a
 * second, independent source.
 *
 * <p>If this test fails, the fixture drifted (regenerate it: 15×15 Gaussian
 * blur of {@code real-face.jpg}) or a PAD source changed — either way the
 * end-to-end PAD rejection in {@code LivenessPipelineIntegrationTest} would no
 * longer exercise what it claims to.</p>
 */
class PrintAttackFixtureTest {

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat decodeFixture() throws Exception {
        try (InputStream in = PrintAttackFixtureTest.class.getClassLoader()
                .getResourceAsStream("fixtures/print-attack.jpg")) {
            assertNotNull(in, "fixtures/print-attack.jpg must be on the test classpath");
            MatOfByte buf = new MatOfByte(in.readAllBytes());
            Mat frame = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR);
            buf.release();
            assertTrue(!frame.empty(), "fixture must decode as an image");
            return frame;
        }
    }

    @Test
    void fixtureKeepsOneFaceAndTripsPadAsPrintedPhoto() throws Exception {
        Mat frame = decodeFixture();
        try {
            ImageUtils imageUtils = new ImageUtils();
            Rect[] faces = imageUtils.detectFaces(frame);
            assertEquals(1, faces.length,
                    "the attack fixture must still present exactly one detectable face");

            PadVerdict heuristic = new PadEngineService().detect(frame, imageUtils);
            assertTrue(heuristic.attackDetected(), "texture heuristic must flag the print attack");
            assertEquals(PadAttackType.PRINTED_PHOTO, heuristic.attackType());

            // Independent second source (detectPad ORs the two): the model must
            // also refuse this frame even though its class label may differ.
            PassiveScoringService scorer = new PassiveScoringService(
                    mock(LivenessEngineService.class), "auto", "");
            Rect f = faces[0];
            Optional<PadVerdict> model = scorer.assessPad(frame,
                    new LivenessEngineService.FaceObservation(true, false, 0.8,
                            new int[]{f.x, f.y, f.width, f.height}));
            assertTrue(model.isPresent() && model.get().attackDetected(),
                    "the model must independently classify the fixture as an attack, got: " + model);
        } finally {
            frame.release();
        }
    }
}
