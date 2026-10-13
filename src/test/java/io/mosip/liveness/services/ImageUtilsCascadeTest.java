package io.mosip.liveness.services;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Haar cascade data files.
 *
 * <p>OpenCV's Java bindings do not ship these XMLs. When they are missing the
 * service does not fail loudly — face detection simply returns nothing and every
 * frame is reported as "no face detected", so no session can ever pass. These
 * tests fail instead of letting that regress silently.</p>
 */
class ImageUtilsCascadeTest {

    @BeforeAll
    static void loadOpenCvNatives() {
        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable t) {
            Assumptions.assumeTrue(false,
                    "OpenCV native library unavailable on this platform: " + t);
        }
    }

    @Test
    void cascadeFilesAreOnTheClasspath() {
        ClassLoader cl = ImageUtilsCascadeTest.class.getClassLoader();
        assertNotNull(cl.getResourceAsStream(ImageUtils.FACE_CASCADE),
                ImageUtils.FACE_CASCADE + " must be bundled in src/main/resources/");
        assertNotNull(cl.getResourceAsStream(ImageUtils.EYE_CASCADE),
                ImageUtils.EYE_CASCADE + " must be bundled in src/main/resources/");
    }

    @Test
    void cascadesLoadIntoUsableClassifiers() {
        ImageUtils utils = new ImageUtils();
        assertTrue(utils.cascadesAvailable(),
                "Haar cascades did not load; face detection would be silently disabled");
    }

    // ---- frame hardening: payload/dimension caps and analysis downscale ----

    @Test
    void oversizedFramePayload_isRejected() {
        ImageUtils utils = new ImageUtils();
        byte[] bomb = new byte[ImageUtils.MAX_FRAME_BYTES + 1];
        String b64 = Base64.getEncoder().encodeToString(bomb);
        assertThrows(ImageUtils.InvalidFrameError.class, () -> utils.decodeBase64Frame(b64),
                "payloads over MAX_FRAME_BYTES must be rejected before decode");
    }

    @Test
    void decompressionBomb_isRejectedByDimensionCap() {
        ImageUtils utils = new ImageUtils();
        String b64 = encodePng(Mat.zeros(9000, 200, CvType.CV_8UC3));
        assertThrows(ImageUtils.InvalidFrameError.class, () -> utils.decodeBase64Frame(b64),
                "an image wider than MAX_FRAME_DIMENSION must be rejected");
    }

    @Test
    void largeFrame_isDownscaledToAnalysisCap() {
        ImageUtils utils = new ImageUtils();
        String b64 = encodePng(Mat.zeros(1080, 1920, CvType.CV_8UC3));
        Mat decoded = utils.decodeBase64Frame(b64);
        try {
            assertEquals(ImageUtils.ANALYSIS_MAX_DIMENSION, decoded.cols(),
                    "1920px-wide input must be scaled to the analysis cap");
            assertEquals(720, decoded.rows(),
                    "aspect ratio must be preserved by the downscale");
        } finally {
            decoded.release();
        }
    }

    @Test
    void smallFrame_passesThroughUnchanged() {
        ImageUtils utils = new ImageUtils();
        String b64 = encodePng(Mat.zeros(480, 640, CvType.CV_8UC3));
        Mat decoded = utils.decodeBase64Frame(b64);
        try {
            assertEquals(640, decoded.cols(),
                    "frames at or below the analysis cap must not be resized");
            assertEquals(480, decoded.rows());
        } finally {
            decoded.release();
        }
    }

    // ---- the configurable cap, and what it must not weaken ----

    @Test
    void configuredAnalysisCap_isHonoured() {
        ImageUtils utils = new ImageUtils(320);
        String b64 = encodePng(Mat.zeros(1080, 1920, CvType.CV_8UC3));
        Mat decoded = utils.decodeBase64Frame(b64);
        try {
            assertEquals(320, decoded.cols(), "a configured cap must replace the 1280 default");
            assertEquals(180, decoded.rows(), "aspect ratio must be preserved by the downscale");
        } finally {
            decoded.release();
        }
    }

    @Test
    void aCapBelowTheFloor_isAConfigurationError() {
        // 64px is the floor: below it the cascade's own 60x60 minimum face size
        // cannot be met, so a smaller value would disable detection instead of
        // saving CPU. Refusing it beats clamping it silently.
        assertThrows(IllegalArgumentException.class, () -> new ImageUtils(63));
        assertThrows(IllegalArgumentException.class, () -> new ImageUtils(0));
    }

    @Test
    void safetyCaps_areUnchangedByTheConfigurableCap() {
        ImageUtils utils = new ImageUtils(320);
        byte[] bomb = new byte[ImageUtils.MAX_FRAME_BYTES + 1];
        assertThrows(ImageUtils.InvalidFrameError.class,
                () -> utils.decodeBase64Frame(Base64.getEncoder().encodeToString(bomb)),
                "the 6 MB payload cap must still reject before decode");
        String huge = encodePng(Mat.zeros(9000, 200, CvType.CV_8UC3));
        assertThrows(ImageUtils.InvalidFrameError.class, () -> utils.decodeBase64Frame(huge),
                "the 8192px dimension cap must still reject after decode");
    }

    /**
     * The console's capture budget is 640px on the long edge at JPEG quality
     * 0.7. This is that frame, produced by the same encoders a browser uses:
     * the fixture upscaled to exactly 640px (so the face is above the cascade's
     * 60x60 minimum), JPEG-compressed at 0.7, then pushed through the real
     * decode path. Exactly one face must still be found, or the client cap
     * would have traded bytes for a face the engine cannot see.
     */
    @Test
    void faceDetectionStillWorksOnA640pxFrameAtQuality70() throws Exception {
        Mat source;
        try (InputStream in = ImageUtilsCascadeTest.class.getClassLoader()
                .getResourceAsStream("fixtures/real-face.jpg")) {
            assertNotNull(in, "the committed face fixture must be on the test classpath");
            MatOfByte raw = new MatOfByte(in.readAllBytes());
            source = Imgcodecs.imdecode(raw, Imgcodecs.IMREAD_COLOR);
            raw.release();
        }
        assertTrue(!source.empty(), "fixture must decode");
        try {
            // Scale the fixture so its long edge is exactly the console's cap
            // (256x320 -> 512x640): the face grows with the frame and stays well
            // above the cascade's 60x60 minimum.
            double scale = 640.0 / Math.max(source.cols(), source.rows());
            Mat sized = new Mat();
            Imgproc.resize(source, sized,
                    new Size(Math.round(source.cols() * scale), Math.round(source.rows() * scale)),
                    0, 0, Imgproc.INTER_AREA);
            assertEquals(640, Math.max(sized.cols(), sized.rows()),
                    "the test frame must be the console's 640px capture cap");

            MatOfInt jpeg70 = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 70);
            MatOfByte jpeg = new MatOfByte();
            Imgcodecs.imencode(".jpg", sized, jpeg, jpeg70);
            jpeg70.release();
            String b64 = Base64.getEncoder().encodeToString(jpeg.toArray());
            jpeg.release();
            sized.release();

            // 640 <= the default cap, so nothing is resized here: this is
            // exactly what the service receives from a console capture.
            ImageUtils utils = new ImageUtils();
            Mat decoded = utils.decodeBase64Frame(b64);
            try {
                assertEquals(640, Math.max(decoded.cols(), decoded.rows()));
                assertEquals(1, utils.detectFaces(decoded).length,
                        "a 640px, quality-0.7 capture must still present exactly one face");
            } finally {
                decoded.release();
            }
        } finally {
            source.release();
        }
    }

    private static String encodePng(Mat mat) {
        MatOfByte buf = new MatOfByte();
        try {
            Imgcodecs.imencode(".png", mat, buf);
            return Base64.getEncoder().encodeToString(buf.toArray());
        } finally {
            buf.release();
            mat.release();
        }
    }
}
