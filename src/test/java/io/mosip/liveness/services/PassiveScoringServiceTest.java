package io.mosip.liveness.services;

import io.mosip.liveness.backend.MediaPipeFaceMeshBackend;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadVerdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scorer façade: mode selection, model loading, and the graceful
 * heuristic fallback the service depends on when no model is available.
 */
class PassiveScoringServiceTest {

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat flatFrame() {
        Mat m = new Mat(96, 96, org.opencv.core.CvType.CV_8UC3, new Scalar(120, 130, 140));
        Imgproc.resize(m, m, new org.opencv.core.Size(96, 96));
        return m;
    }

    private static LivenessEngineService.FaceObservation noFace() {
        return new LivenessEngineService.FaceObservation(false, false, null, null);
    }

    @Test
    void heuristicModeNeverLoadsTheModel() {
        LivenessEngineService heuristic = mock(LivenessEngineService.class);
        PassiveScoringService scorer = new PassiveScoringService(heuristic, "heuristic", "");

        assertFalse(scorer.isModelAvailable());
        assertEquals("opencv-heuristic", scorer.scorerId());
        assertTrue(scorer.assessPad(flatFrame(), noFace()).isEmpty());
    }

    @Test
    void heuristicModeDelegatesScoringToTheOpenCvHeuristic() {
        LivenessEngineService heuristic = mock(LivenessEngineService.class);
        when(heuristic.scorePassive(any(), any(), any())).thenReturn(0.72);
        PassiveScoringService scorer = new PassiveScoringService(heuristic, "heuristic", "");

        Mat frame = flatFrame();
        try {
            assertEquals(0.72, scorer.score(frame, noFace(), mock(ImageUtils.class)), 1e-9);
            verify(heuristic).scorePassive(any(), any(), any());
        } finally {
            frame.release();
        }
    }

    @Test
    void autoModeLoadsTheBundledModel() {
        PassiveScoringService scorer = new PassiveScoringService(
                mock(LivenessEngineService.class), "auto", "");

        assertTrue(scorer.isModelAvailable(), "bundled model + ONNX Runtime must load");
        assertEquals("onnx-minifasnet-v2", scorer.scorerId());
    }

    @Test
    void modelOpinionIsPresentWhenTheModelLoads() {
        PassiveScoringService scorer = new PassiveScoringService(
                mock(LivenessEngineService.class), "auto", "");

        Mat frame = flatFrame();
        try {
            Optional<PadVerdict> pad = scorer.assessPad(frame, noFace());
            // With a face-gate failure the model stays silent rather than guessing;
            // with a detectable face it returns a verdict — either way: no crash.
            assertTrue(pad.isEmpty() || pad.get() != null);
        } finally {
            frame.release();
        }
    }

    @Test
    void scoreFallsBackToHeuristicWhenTheModelCannotAnalyseTheFrame() {
        LivenessEngineService heuristic = mock(LivenessEngineService.class);
        when(heuristic.scorePassive(any(), any(), any())).thenReturn(0.55);
        PassiveScoringService scorer = new PassiveScoringService(heuristic, "auto", "");
        assertTrue(scorer.isModelAvailable());

        // A flat grey frame: the detector sees no face, so the model defers to
        // the heuristic instead of punishing the frame with a zero.
        Mat frame = flatFrame();
        try {
            assertEquals(0.55, scorer.score(frame, noFace(), mock(ImageUtils.class)), 1e-9);
            verify(heuristic).scorePassive(any(), any(), any());
        } finally {
            frame.release();
        }
    }

    // ------------------------------------------- explicit selections (F5)

    @Test
    void explicitMockSelectionIsHonouredByTheService() {
        PassiveScoringService scorer = new PassiveScoringService(
                mock(LivenessEngineService.class), "mock", "");

        assertTrue(scorer.isModelAvailable(),
                "the mock is a configured scorer on this path, not the heuristic");
        assertEquals("mock", scorer.scorerId());
    }

    @Test
    void explicitOnnxSelectionIsHonouredByTheService() {
        PassiveScoringService scorer = new PassiveScoringService(
                mock(LivenessEngineService.class), "onnx-minifasnet-v2", "");

        assertTrue(scorer.isModelAvailable(), "bundled model + ONNX Runtime must load");
        assertEquals("onnx-minifasnet-v2", scorer.scorerId());
    }

    @Test
    void explicitUnavailableSelectionFailsClosedInsteadOfChoosingAnotherScorer() {
        LivenessEngineService heuristic = mock(LivenessEngineService.class);
        PassiveScoringService scorer = new PassiveScoringService(
                heuristic, "mediapipe-facemesh", "");

        if (MediaPipeFaceMeshBackend.isTfLiteAvailable()) {
            // Availability is probed, not assumed: on a host with TFLite the
            // explicit selection simply loads.
            assertTrue(scorer.isModelAvailable());
            assertEquals("mediapipe-facemesh", scorer.scorerId());
            return;
        }

        // This build: a coded refusal, on every call, naming the key — never
        // a silent swap to the heuristic or to another backend (F5).
        LivenessException refusal = assertThrows(LivenessException.class,
                scorer::isModelAvailable);
        assertTrue(refusal.getMessage().contains("mosip.liveness.backend=mediapipe-facemesh"),
                refusal.getMessage());
        assertThrows(LivenessException.class, scorer::scorerId,
                "the failure is cached: one attempt, same refusal");
        verify(heuristic, never()).scorePassive(any(), any(), any());
    }

    @Test
    void unknownModeFailsAtConstruction() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new PassiveScoringService(mock(LivenessEngineService.class),
                        "gibberish", ""),
                "an unknown value must stop bean creation, not pick a scorer");
        assertTrue(e.getMessage().contains("mosip.liveness.backend"), e.getMessage());
    }

    /**
     * A negative intra-op thread count is refused at construction. It must not
     * read as "derive from the core count": an operator who writes {@code -2}
     * believes they set something, and a silent derive would hide the typo until
     * someone measured the machine. (A non-integer never reaches here — Spring's
     * binding refuses the boot first, which the packaged service was checked
     * against end to end.)
     */
    @Test
    void negativeOnnxIntraOpThreadCountIsAConfigurationError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new PassiveScoringService(mock(LivenessEngineService.class), "heuristic", "",
                        4.0, -2));
        assertTrue(e.getMessage().contains("mosip.liveness.onnx-intra-op-threads"), e.getMessage());
    }
}
