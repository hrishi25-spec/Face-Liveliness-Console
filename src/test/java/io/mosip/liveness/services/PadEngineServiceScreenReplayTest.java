package io.mosip.liveness.services;

import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the screen-replay band energy: a live face and a printed photo
 * stay below the attack threshold, while a periodic screen surface — the signal
 * a phone or monitor replay adds to a captured face — crosses it and is typed as
 * {@link PadAttackType#SCREEN_REPLAY}.
 *
 * <p>The screen fixture here is synthetic (a fine LCD-like grid over a
 * luminance ramp), not a photograph of a phone: no real biometric data is added
 * by this test. It pins the band's shape and the threshold's side of the line;
 * a real device still needs its own calibration, as the Python reference
 * says.</p>
 */
class PadEngineServiceScreenReplayTest {

    private final PadEngineService pad = new PadEngineService();
    private final ImageUtils imageUtils = new ImageUtils();

    @BeforeAll
    static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    void liveFaceIsNotAScreenReplay() {
        Mat frame = fixture("real-face.jpg");
        try {
            double ratio = grayRatio(frame);
            assertTrue(ratio < PadEngineService.FREQ_ENERGY_ATTACK_THRESHOLD,
                    "a live face must sit below the screen band, ratio=" + ratio);
            PadVerdict verdict = pad.detect(frame, imageUtils);
            assertFalse(verdict.attackDetected(), "a live face is not a presentation attack");
        } finally {
            frame.release();
        }
    }

    @Test
    void printedPhotoStaysAPrintAttackNotAScreenReplay() {
        Mat frame = fixture("print-attack.jpg");
        try {
            double ratio = grayRatio(frame);
            assertTrue(ratio < PadEngineService.FREQ_ENERGY_ATTACK_THRESHOLD,
                    "the flattened print fixture must not read as a screen, ratio=" + ratio);
            PadVerdict verdict = pad.detect(frame, imageUtils);
            assertTrue(verdict.attackDetected(), "the print fixture must still be refused");
            assertEquals(PadAttackType.PRINTED_PHOTO, verdict.attackType());
        } finally {
            frame.release();
        }
    }

    @Test
    void fineScreenSurfaceIsScreenReplay() {
        Mat frame = screenPattern();
        try {
            double ratio = grayRatio(frame);
            assertTrue(ratio > PadEngineService.FREQ_ENERGY_ATTACK_THRESHOLD,
                    "a fine screen grid must cross the band, ratio=" + ratio);
            PadVerdict verdict = pad.detect(frame, imageUtils);
            assertTrue(verdict.attackDetected(), "a screen surface is a presentation attack");
            assertEquals(PadAttackType.SCREEN_REPLAY, verdict.attackType());
        } finally {
            frame.release();
        }
    }

    private static Mat fixture(String name) {
        Mat frame = Imgcodecs.imread("src/test/resources/fixtures/" + name);
        assertFalse(frame.empty(), name + " must decode");
        return frame;
    }

    private static double grayRatio(Mat bgr) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            return PadEngineService.frequencyEnergyRatio(gray);
        } finally {
            gray.release();
        }
    }

    /** A fine LCD-like grid over a slowly varying luminance ramp, at capture size. */
    private static Mat screenPattern() {
        int width = 640;
        int height = 360;
        Mat mat = new Mat(height, width, CvType.CV_8UC3, new Scalar(0));
        byte[] row = new byte[width * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = 110 + (x * 40) / width;
                int grid = (x % 3 == 0 || y % 3 == 0) ? 55 : 0;
                int v = Math.min(255, base + grid);
                row[x * 3] = (byte) v;
                row[x * 3 + 1] = (byte) v;
                row[x * 3 + 2] = (byte) v;
            }
            mat.put(y, 0, row);
        }
        return mat;
    }
}
