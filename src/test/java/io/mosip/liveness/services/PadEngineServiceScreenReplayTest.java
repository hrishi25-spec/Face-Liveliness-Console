package io.mosip.liveness.services;

import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
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
    void defocusedPhoneWithAVisibleBezelIsAScreenReplayNotAPrint() {
        // Defocus flattens the frame enough for the print rule to fire (asserted
        // below); the bezel is what keeps the type right, because a display is a
        // dark body around a lit panel even when nothing on it is in focus.
        Mat frame = phoneWithBezel(1.6);
        try {
            Mat gray = new Mat();
            try {
                Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
                assertTrue(textureVariance(gray) < 15.0,
                        "the fixture must be flat enough to be called a print");
                assertTrue(PadEngineService.screenBezelStrength(gray) > 0.0,
                        "the phone body around the panel is bezel evidence");
            } finally {
                gray.release();
            }
            PadVerdict verdict = pad.detect(frame, imageUtils);
            assertTrue(verdict.attackDetected(), "a defocused phone screen is a presentation attack");
            assertEquals(PadAttackType.SCREEN_REPLAY, verdict.attackType());
        } finally {
            frame.release();
        }
    }

    @Test
    void printedPhotoHasNoBezelEvidence() {
        Mat frame = fixture("print-attack.jpg");
        try {
            Mat gray = new Mat();
            try {
                Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
                assertEquals(0.0, PadEngineService.screenBezelStrength(gray),
                        "a print has no dark body held in front of a lit room");
            } finally {
                gray.release();
            }
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

    /** Laplacian variance - the texture rule's own signal, reproduced for the test. */
    private static double textureVariance(Mat gray) {
        Mat laplacian = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        try {
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
            Core.meanStdDev(laplacian, mean, stddev);
            return stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
        } finally {
            laplacian.release();
            mean.release();
            stddev.release();
        }
    }

    /**
     * A portrait filling a phone screen with a dark body around it, in a lit
     * room: the capture a webcam sees when a phone holds the replay. Blurred by
     * {@code defocus} to exercise the case the texture rule misreads.
     */
    private static Mat phoneWithBezel(double defocus) {
        int width = 1280, height = 720, x = 430, y = 30, screenW = 420, screenH = 660;
        Mat frame = new Mat(height, width, CvType.CV_8UC3, new Scalar(120, 120, 120));
        Mat body = new Mat(frame, new Rect(x - 14, y - 14, screenW + 28, screenH + 28));
        body.setTo(new Scalar(6, 6, 8));
        body.release();
        Mat portrait = Imgcodecs.imread("src/test/resources/fixtures/real-face.jpg");
        Mat screen = new Mat(frame, new Rect(x, y, screenW, screenH));
        Mat scaled = new Mat();
        try {
            Imgproc.resize(portrait, scaled, new Size(screenW, screenH));
            scaled.copyTo(screen);
            Imgproc.GaussianBlur(frame, frame, new Size(5, 5), defocus);
        } finally {
            screen.release();
            scaled.release();
            portrait.release();
        }
        return frame;
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
