package io.mosip.liveness.services;

import io.mosip.liveness.metrics.PipelineTimers;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * Shared helpers for decoding and quality-checking incoming frames.
 * Maps to the Python framework's image_utils.py.
 *
 * <p>Haar cascade data files are resolved from the classpath first (drop them in
 * {@code src/main/resources/}) and then from the filesystem, so the same build
 * works when the service is launched from any working directory.</p>
 */
@Service
public class ImageUtils {

    private static final Logger log = LoggerFactory.getLogger(ImageUtils.class);

    static final String FACE_CASCADE = "haarcascade_frontalface_default.xml";
    static final String EYE_CASCADE = "haarcascade_eye.xml";

    /** Hard cap on a decoded (binary) frame payload, before base64. */
    static final int MAX_FRAME_BYTES = 6_000_000;
    /** Hard cap per image dimension — guards against decompression bombs
     *  (a tiny PNG that expands to a huge bitmap). */
    static final int MAX_FRAME_DIMENSION = 8192;
    /** Hard cap on total pixels (cols*rows) for the same reason. */
    static final long MAX_FRAME_PIXELS = 40_000_000L;
    /** Frames larger than this are downscaled before analysis so a 1080p/4K
     *  camera costs the same CPU as a 640x480 one (low-end devices).
     *
     *  <p>This is the <b>default</b> of {@code mosip.liveness.analysis-max-dimension};
     *  the cap in force is the instance field {@link #analysisMaxDimension}. The
     *  value is a cost bound, not a safety bound — the payload, dimension and
     *  pixel caps above stay fixed and are applied before it.</p> */
    static final int ANALYSIS_MAX_DIMENSION = 1280;

    /** Lowest accepted configured cap: below this the cascade's own 60x60
     *  minimum face size can no longer be met reliably, so a smaller value
     *  would quietly disable detection instead of saving CPU. */
    static final int MIN_ANALYSIS_MAX_DIMENSION = 64;

    /**
     * Long-edge cap in force for this instance. Applied to every decoded frame
     * inside {@link #decodeBase64Frame(String)} — after the payload, dimension
     * and pixel caps have accepted the frame, and before the caller runs face
     * detection, quality scoring or PAD, so no analysis ever sees more pixels
     * than this.
     */
    private final int analysisMaxDimension;

    /**
     * Spring wiring: the analysis cap is configurable because it is a CPU/bytes
     * trade, not a policy decision. A value below
     * {@link #MIN_ANALYSIS_MAX_DIMENSION} is a configuration error rather than a
     * silent clamp — clamping would look like the setting took effect while
     * analysis quietly stopped finding faces.
     */
    @Autowired
    public ImageUtils(@Value("${mosip.liveness.analysis-max-dimension:" + ANALYSIS_MAX_DIMENSION + "}")
                      int analysisMaxDimension) {
        if (analysisMaxDimension < MIN_ANALYSIS_MAX_DIMENSION) {
            throw new IllegalArgumentException("mosip.liveness.analysis-max-dimension must be at least "
                    + MIN_ANALYSIS_MAX_DIMENSION + ", was " + analysisMaxDimension);
        }
        this.analysisMaxDimension = analysisMaxDimension;
    }

    /** Direct construction (tests, eval tools): the default cap. */
    public ImageUtils() {
        this(ANALYSIS_MAX_DIMENSION);
    }

    // Lazy-load cascades so Mockito can proxy this class without triggering native lib loading
    private volatile CascadeClassifier faceCascade;
    private volatile CascadeClassifier eyeCascade;

    private CascadeClassifier getFaceCascade() {
        if (faceCascade == null) {
            synchronized (this) {
                if (faceCascade == null) {
                    faceCascade = loadCascade(FACE_CASCADE);
                }
            }
        }
        return faceCascade;
    }

    private CascadeClassifier getEyeCascade() {
        if (eyeCascade == null) {
            synchronized (this) {
                if (eyeCascade == null) {
                    eyeCascade = loadCascade(EYE_CASCADE);
                }
            }
        }
        return eyeCascade;
    }

    /**
     * Resolve a Haar cascade from the classpath, then the filesystem.
     *
     * <p>These XML files are not shipped with OpenCV's Java bindings, so a build
     * without them cannot detect faces and every frame will be reported as
     * "no face detected". An empty classifier is returned in that case (rather
     * than throwing) so the rest of the service still serves traffic.</p>
     */
    private CascadeClassifier loadCascade(String resourceName) {
        // 1) classpath — the preferred location; survives packaging and any CWD
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in != null) {
                Path tmp = Files.createTempFile("cascade-", ".xml");
                try {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    // CascadeClassifier reads the whole document at construction,
                    // so the temp file can be removed immediately afterwards.
                    CascadeClassifier classifier = new CascadeClassifier(tmp.toString());
                    if (!classifier.empty()) {
                        log.debug("Loaded cascade '{}' from classpath", resourceName);
                        return classifier;
                    }
                } finally {
                    // Best-effort: on Windows a file still held open cannot be
                    // removed, which must not turn a successful load into a failure.
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (Exception ignored) {
                        tmp.toFile().deleteOnExit();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load cascade '{}' from the classpath", resourceName, e);
        }

        // 2) filesystem — absolute path, or relative to the current working directory
        try {
            CascadeClassifier classifier = new CascadeClassifier(resourceName);
            if (!classifier.empty()) {
                log.debug("Loaded cascade '{}' from the filesystem", resourceName);
                return classifier;
            }
        } catch (Exception e) {
            log.warn("Failed to load cascade '{}' from the filesystem", resourceName, e);
        }

        log.warn("Haar cascade '{}' was not found on the classpath or filesystem. Face "
                + "detection is DISABLED, so no frame can pass passive liveness. Add the "
                + "file to src/main/resources/ (OpenCV data directory) to enable it.",
                resourceName);
        return new CascadeClassifier(); // empty; .empty() returns true
    }

    /**
     * True when both Haar cascades loaded and face/eye detection is usable.
     * Package-private so tests can assert the cascade data is present.
     */
    boolean cascadesAvailable() {
        CascadeClassifier face = getFaceCascade();
        CascadeClassifier eye = getEyeCascade();
        return face != null && !face.empty() && eye != null && !eye.empty();
    }

    public static class InvalidFrameError extends RuntimeException {
        public InvalidFrameError(String message) {
            super(message);
        }
    }

    public Mat decodeBase64Frame(String frameBase64) {
        // Timed as a whole, including the rejection guards: an oversized or
        // undecodable frame is a cost this path still pays, and hiding it would
        // make decode look free under attack.
        return PipelineTimers.timed(PipelineTimers.DECODE, () -> decodeFrame(frameBase64));
    }

    private Mat decodeFrame(String frameBase64) {
        try {
            if (frameBase64.length() > 64 && frameBase64.contains(",")) {
                frameBase64 = frameBase64.substring(frameBase64.indexOf(',') + 1);
            }
            byte[] raw = Base64.getDecoder().decode(frameBase64);
            if (raw.length == 0) {
                throw new InvalidFrameError("Empty frame payload");
            }
            if (raw.length > MAX_FRAME_BYTES) {
                throw new InvalidFrameError("Frame payload exceeds " + MAX_FRAME_BYTES + " bytes");
            }
            MatOfByte matOfByte = new MatOfByte(raw);
            Mat image;
            try {
                image = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);
            } finally {
                matOfByte.release();       // even an undecodable payload holds a native buffer
            }
            if (image.empty()) {
                image.release();
                throw new InvalidFrameError("Frame could not be decoded as an image");
            }
            if (image.cols() > MAX_FRAME_DIMENSION || image.rows() > MAX_FRAME_DIMENSION
                    || (long) image.cols() * image.rows() > MAX_FRAME_PIXELS) {
                image.release();
                throw new InvalidFrameError("Frame dimensions exceed supported limits");
            }
            return downscaleForAnalysis(image);
        } catch (InvalidFrameError e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidFrameError("Frame is not valid base64 data: " + e.getMessage());
        }
    }

    /**
     * Bound the CPU cost of face detection / quality scoring: Haar cascade and
     * Laplacian work scale with pixel count, so a 4K frame would cost ~9x a
     * 720p one. Frames above {@link #analysisMaxDimension} are scaled down
     * (INTER_AREA, quality-preserving) so every input resolution analyses at
     * the same cost. Small frames (the console's 640x480, and anything the
     * client-side capture cap has already shrunk) pass through untouched.
     *
     * <p>The original JPEG decode cannot be avoided by this cap — pixels are
     * needed before any resize — so a client that sends smaller frames saves
     * the decode as well; this cap only bounds what every <em>later</em> stage
     * pays.</p>
     */
    private Mat downscaleForAnalysis(Mat image) {
        int maxSide = Math.max(image.cols(), image.rows());
        if (maxSide <= analysisMaxDimension) {
            return image;
        }
        double scale = (double) analysisMaxDimension / maxSide;
        Size target = new Size(
                Math.max(1, Math.round(image.cols() * scale)),
                Math.max(1, Math.round(image.rows() * scale)));
        Mat small = new Mat();
        Imgproc.resize(image, small, target, 0, 0, Imgproc.INTER_AREA);
        image.release();
        return small;
    }

    public Rect[] detectFaces(Mat image) {
        return PipelineTimers.timed(PipelineTimers.FACE_DETECT, () -> detectFacesInternal(image));
    }

    private Rect[] detectFacesInternal(Mat image) {
        CascadeClassifier cascade = getFaceCascade();
        if (cascade == null || cascade.empty()) {
            return new Rect[0];
        }
        // The cascade itself is loaded once per ImageUtils instance (see
        // getFaceCascade): constructing one reads and parses the whole XML, which
        // is far too expensive for a per-frame path. The buffers created here are
        // per call and released in the finally blocks — MatOfRect *is* a Mat, so
        // leaving one behind leaks native memory on every frame.
        Mat gray = new Mat();
        MatOfRect faces = new MatOfRect();
        try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            cascade.detectMultiScale(gray, faces, 1.1, 5, 0, new Size(60, 60));
            return faces.toArray();
        } finally {
            faces.release();
            gray.release();
        }
    }

    public Rect[] detectEyes(Mat faceGrayRoi) {
        CascadeClassifier cascade = getEyeCascade();
        if (cascade == null || cascade.empty()) {
            return new Rect[0];
        }
        MatOfRect eyes = new MatOfRect();
        try {
            cascade.detectMultiScale(faceGrayRoi, eyes, 1.1, 8);
            return eyes.toArray();
        } finally {
            eyes.release();
        }
    }

    public double sharpnessScore(Mat image) {
        Mat gray = new Mat();
        Mat laplacian = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
            Core.meanStdDev(laplacian, mean, stddev);   // mean is required by the API, unused here
            double variance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
            return Math.max(0.0, Math.min(1.0, variance / 500.0));
        } finally {
            gray.release();
            laplacian.release();
            mean.release();
            stddev.release();
        }
    }

    public double brightnessScore(Mat image) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            double meanVal = Core.mean(gray).val[0];
            return Math.max(0.0, Math.min(1.0, 1.0 - Math.abs(meanVal - 128.0) / 128.0));
        } finally {
            gray.release();
        }
    }
}
