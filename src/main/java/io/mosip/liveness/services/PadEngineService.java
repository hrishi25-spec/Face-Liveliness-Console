package io.mosip.liveness.services;

import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.metrics.PipelineTimers;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Presentation Attack Detection (PAD) engine service.
 * Maps to the Python framework's pad_engine.py.
 * Uses cheap OpenCV heuristics as placeholders for a real ISO/IEC 30107-3 model.
 *
 * <p>Two independent signals, both single-frame and cheap enough for the
 * per-frame path:</p>
 * <ul>
 *   <li><b>Screen replay / moiré</b> — the share of the non-DC spectrum that
 *       sits in the outer half of the frequency plane. A screen whose pixel
 *       grid survives the capture is much richer in high frequencies than a
 *       live face. This is a backstop, not the primary screen-replay signal: at
 *       console capture resolution a photographed phone screen often averages
 *       its grid away and scores like a live face, which is why MiniFASNet
 *       decides the replay case (see {@code DecisionEngineService.detectPad}).</li>
 *   <li><b>Printed photo</b> — Laplacian variance, which collapses when the
 *       presentation is flat printed paper rather than skin, hair and eyes.</li>
 *   <li><b>Screen bezel</b> — a large, near-black rectangular body that keeps a
 *       margin from the frame edge, sits in a brighter room, and holds a
 *       brighter panel inside a thin dark band. It is the tie-breaker the
 *       texture rule needs: a defocused phone screen is flat (so the print rule
 *       fires) but still has a bezel, while a printed sheet does not.</li>
 * </ul>
 */
@Service
public class PadEngineService {

    /**
     * Edge of the square spectrum the band energy is measured on. 512 keeps a
     * screen's pixel grid in the measured band — at 256 the downscale averages
     * it away and a phone replay can score lower than a live face — while the
     * DFT stays affordable on the 2-core target hardware. The frame is resized
     * first, so a 1080p capture costs the same as a 640×480 one.
     */
    private static final int SPECTRUM_EDGE = 512;

    /**
     * Share of the non-DC spectrum that must sit in the outer half of the
     * frequency plane before the frame is called a screen replay.
     *
     * <p>Calibrated against {@code fixtures/real-face.jpg} (a live face, ~0.25)
     * and a synthetic fine periodic grid at capture size (~0.61). A frame of a
     * face on a phone screen in a 1280&times;720 capture measures ~0.21, i.e.
     * <b>below</b> this line — the 640 px console capture averages the screen's
     * grid away — so this rule does not replace the model for a real replay. It
     * catches a surface whose grid is still visible, and the threshold is
     * deliberately above the live-face value so it never fires on one. As the
     * Python reference notes, this is a proxy, not an ISO-evaluated model: real
     * capture hardware needs its own calibration before the number means
     * anything on that device.</p>
     */
    static final double FREQ_ENERGY_ATTACK_THRESHOLD = 0.45;

    /** Radius, in bins, of the outer half of the (fftshift-centred) plane. */
    private static final double BAND_RADIUS = SPECTRUM_EDGE / 4.0;

    /**
     * Which spectrum bins are in that outer band, precomputed once: distance
     * from the centre in wrapped (fftshift) coordinates, so the unshifted DFT
     * output does not misclassify the wrapped Nyquist edge as low frequency.
     */
    private static final boolean[] OUTER_BAND = buildOuterBand();

    /**
     * Laplacian variance below which the frame is too flat to be a live face —
     * the print-attack proxy. Calibrated against the printed-photo fixture, which
     * must still be caught, so this is not a knob to loosen casually.
     */
    private static final double TEXTURE_VARIANCE_ATTACK_THRESHOLD = 15.0;

    /** Working width of the bezel pass; the bezel survives this downscale. */
    private static final int BEZEL_WORKING_WIDTH = 480;
    /** Grey level below which a pixel belongs to a dark body, not a lit panel or room. */
    private static final int BEZEL_DARK_LEVEL = 45;
    /** Smallest dark blob (share of the frame) that can be a device body. */
    private static final double BEZEL_MIN_AREA_SHARE = 0.12;
    /** A body fills its bounding box; a face or a suit does not. */
    private static final double BEZEL_MIN_RECT_FILL = 0.80;
    /** The room around the body must be this much brighter than the bezel. */
    private static final int BEZEL_MIN_ROOM_CONTRAST = 30;
    /** The panel inside the bezel must be this much brighter than the bezel. */
    private static final int BEZEL_MIN_PANEL_CONTRAST = 30;
    /** The bezel band itself: dark and smooth. */
    private static final int BEZEL_MAX_BAND_MEAN = 45;
    private static final int BEZEL_MAX_BAND_STD = 20;
    /** A dark blob touching this margin is scenery, not a body held in front of the camera. */
    private static final int BEZEL_FRAME_MARGIN = 3;

    /**
     * Analyse a single frame and return a presentation-attack verdict.
     */
    public PadVerdict detect(Mat frame, ImageUtils imageUtils) {
        return PipelineTimers.timed(PipelineTimers.PAD_HEURISTIC, () -> detectInternal(frame, imageUtils));
    }

    private PadVerdict detectInternal(Mat frame, ImageUtils imageUtils) {
        Mat gray = new Mat();
        Mat laplacian = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        double textureVariance;
        double freqRatio;
        double bezelStrength = 0.0;
        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);

            // Screen-replay / moire proxy via high-frequency spectral energy
            freqRatio = frequencyEnergyRatio(gray);

            // Print-attack proxy via local texture variance
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
            Core.meanStdDev(laplacian, mean, stddev);
            textureVariance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];

            // Only a frame the texture rule would call a print needs the tie-break:
            // a bezel says the flat presentation is a display, not paper.
            if (textureVariance < TEXTURE_VARIANCE_ATTACK_THRESHOLD) {
                bezelStrength = screenBezelStrength(gray);
            }
        } finally {
            // Every native buffer per frame; a throw here must not leave them behind.
            gray.release();
            laplacian.release();
            mean.release();
            stddev.release();
        }

        if (freqRatio > FREQ_ENERGY_ATTACK_THRESHOLD) {
            return PadVerdict.attack(PadAttackType.SCREEN_REPLAY, Math.min(1.0, freqRatio));
        }
        if (textureVariance < TEXTURE_VARIANCE_ATTACK_THRESHOLD) {
            if (bezelStrength > 0.0) {
                return PadVerdict.attack(PadAttackType.SCREEN_REPLAY, bezelStrength);
            }
            double confidence = Math.min(1.0, 1.0 - (textureVariance / TEXTURE_VARIANCE_ATTACK_THRESHOLD));
            return PadVerdict.attack(PadAttackType.PRINTED_PHOTO, confidence);
        }
        // No brightness rule. An under-exposed frame is a *capture* problem —
        // owned by the face-quality gate, which asks for another frame — not
        // evidence of a presentation attack. Treating mean brightness below 0.15
        // as VIDEO_REPLAY flagged the first frames of a capture (a webcam still
        // auto-exposing from black) and, once confirmed over consecutive frames,
        // rejected an honest session before a single frame could be scored.

        // Bona fide
        double margin = Math.min(1.0,
                (FREQ_ENERGY_ATTACK_THRESHOLD - freqRatio) / FREQ_ENERGY_ATTACK_THRESHOLD);
        return PadVerdict.bonaFide(Math.max(0.5, margin));
    }

    /**
     * Screen-bezel evidence: a large, roughly rectangular dark body that keeps a
     * margin from the frame edge, sits in a brighter room, and holds a brighter
     * panel inside a thin dark band.
     *
     * <p>This is the cue that separates a display from printed paper when the
     * texture rule cannot: a phone or monitor is a near-black body around a lit
     * panel, held up in front of a lit room. The frame is downscaled and blurred
     * first, so focus does not decide it — the bezel is a low-frequency step
     * that survives defocus. A dark blob that runs into the frame edge is
     * scenery (a suit, a desk, a shadow) and is rejected.</p>
     *
     * <p>Known look-alike: a dark-framed picture or mirror on a wall has the same
     * shape. This only chooses between {@code PRINTED_PHOTO} and
     * {@code SCREEN_REPLAY} for a frame already flagged as an attack; it never
     * turns an attack into a pass, so a mislabel costs the audit wording, not
     * the decision.</p>
     *
     * @return strength in {@code [0,1]}; 0 when the frame holds no such body
     */
    static double screenBezelStrength(Mat gray) {
        Mat small = new Mat();
        Mat blurred = new Mat();
        Mat dark = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            double scale = Math.min(1.0, BEZEL_WORKING_WIDTH / (double) Math.max(1, gray.cols()));
            Imgproc.resize(gray, small,
                    new Size(Math.max(1, (int) Math.round(gray.cols() * scale)),
                             Math.max(1, (int) Math.round(gray.rows() * scale))),
                    0, 0, Imgproc.INTER_AREA);
            Imgproc.GaussianBlur(small, blurred, new Size(5, 5), 0);
            Imgproc.threshold(blurred, dark, BEZEL_DARK_LEVEL, 255, Imgproc.THRESH_BINARY_INV);
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            try {
                Imgproc.morphologyEx(dark, dark, Imgproc.MORPH_CLOSE, kernel);
            } finally {
                kernel.release();
            }
            Imgproc.findContours(dark, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double frameArea = (double) small.rows() * small.cols();
            Rect body = null;
            double bodyArea = 0.0;
            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                double area = Imgproc.contourArea(contour);
                if (area < BEZEL_MIN_AREA_SHARE * frameArea) continue;
                if (rect.x < BEZEL_FRAME_MARGIN || rect.y < BEZEL_FRAME_MARGIN
                        || rect.x + rect.width > small.cols() - BEZEL_FRAME_MARGIN
                        || rect.y + rect.height > small.rows() - BEZEL_FRAME_MARGIN) continue;
                if (area / (rect.width * (double) rect.height) < BEZEL_MIN_RECT_FILL) continue;
                if (area > bodyArea) {
                    body = rect;
                    bodyArea = area;
                }
            }
            if (body == null) return 0.0;

            double[] bezel = bandStats(small, body, 1, 2, true);
            if (bezel[0] > BEZEL_MAX_BAND_MEAN || bezel[1] > BEZEL_MAX_BAND_STD) return 0.0;
            double[] room = bandStats(small, body, 3, 7, false);
            if (room[0] - bezel[0] < BEZEL_MIN_ROOM_CONTRAST) return 0.0;
            double panel = centreMean(small, body);
            if (panel - bezel[0] < BEZEL_MIN_PANEL_CONTRAST) return 0.0;

            double contrast = Math.min(panel, room[0]) - bezel[0];
            return Math.max(0.5, Math.min(1.0, contrast / 100.0));
        } finally {
            small.release();
            blurred.release();
            dark.release();
            hierarchy.release();
            for (MatOfPoint contour : contours) contour.release();
        }
    }

    /**
     * Mean and standard deviation of the band {@code from..from+depth} pixels
     * inside or outside {@code rect}, as the difference between the two rings.
     */
    private static double[] bandStats(Mat gray, Rect rect, int from, int depth, boolean inside) {
        int sign = inside ? -1 : 1;
        Rect nearer = expand(rect, sign * from, gray.cols(), gray.rows());
        Rect farther = expand(rect, sign * (from + depth), gray.cols(), gray.rows());
        // The band is the difference of the two rects, so the larger one is
        // painted first: an inside band is nearer-bigger, an outside band is
        // farther-bigger, and painting them in the wrong order erases the band.
        Rect larger = inside ? nearer : farther;
        Rect smaller = inside ? farther : nearer;
        Mat mask = new Mat(gray.rows(), gray.cols(), CvType.CV_8UC1, new Scalar(0));
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        try {
            Imgproc.rectangle(mask, larger, new Scalar(255), -1);
            Imgproc.rectangle(mask, smaller, new Scalar(0), -1);
            Core.meanStdDev(gray, mean, stddev, mask);
            return new double[]{mean.get(0, 0)[0], stddev.get(0, 0)[0]};
        } finally {
            mask.release();
            mean.release();
            stddev.release();
        }
    }

    /** Mean brightness of the central fifth of the rect - the panel, not the bezel. */
    private static double centreMean(Mat gray, Rect rect) {
        int insetX = Math.max(1, rect.width / 5);
        int insetY = Math.max(1, rect.height / 5);
        Mat roi = gray.submat(new Rect(rect.x + insetX, rect.y + insetY,
                Math.max(1, rect.width - 2 * insetX), Math.max(1, rect.height - 2 * insetY)));
        try {
            return Core.mean(roi).val[0];
        } finally {
            roi.release();
        }
    }

    /** Grow (positive {@code px}) or shrink (negative) a rect, clamped to the image. */
    private static Rect expand(Rect rect, int px, int width, int height) {
        int x = Math.max(0, rect.x - px);
        int y = Math.max(0, rect.y - px);
        int x2 = Math.min(width, rect.x + rect.width + px);
        int y2 = Math.min(height, rect.y + rect.height + px);
        return new Rect(x, y, Math.max(1, x2 - x), Math.max(1, y2 - y));
    }

    /**
     * Share of the non-DC spectral magnitude that sits in the outer half of the
     * frequency plane, in {@code [0,1]}.
     *
     * <p>The DFT runs on a square, downscaled copy of the frame and with
     * {@code DFT_COMPLEX_OUTPUT}, because a real-valued 1-channel input otherwise
     * comes back in OpenCV's packed conjugate-symmetric layout, which is not a
     * spectrum that can be summed by band. {@code INTER_AREA} is the right
     * prefilter for a downscale: it averages each source block, so the ratio
     * measures the frame's own detail instead of aliasing introduced by the
     * measurement.</p>
     *
     * <p>Package-private so a unit test can pin the band against a live-face
     * fixture and a periodic screen pattern without going through a verdict.</p>
     */
    static double frequencyEnergyRatio(Mat gray) {
        Mat small = new Mat();
        Mat grayFloat = new Mat();
        Mat spectrum = new Mat();
        try {
            Imgproc.resize(gray, small,
                    new Size(SPECTRUM_EDGE, SPECTRUM_EDGE), 0, 0, Imgproc.INTER_AREA);
            small.convertTo(grayFloat, CvType.CV_32F);
            Core.dft(grayFloat, spectrum, Core.DFT_COMPLEX_OUTPUT);

            float[] row = new float[SPECTRUM_EDGE * 2];
            double total = 0.0;
            double outer = 0.0;
            double dc = 0.0;
            for (int y = 0; y < SPECTRUM_EDGE; y++) {
                spectrum.get(y, 0, row);
                for (int x = 0; x < SPECTRUM_EDGE; x++) {
                    double magnitude = Math.hypot(row[2 * x], row[2 * x + 1]);
                    total += magnitude;
                    if (x == 0 && y == 0) {
                        dc = magnitude;      // the mean of the frame, not detail
                    } else if (OUTER_BAND[y * SPECTRUM_EDGE + x]) {
                        outer += magnitude;
                    }
                }
            }
            return outer / Math.max(1.0, total - dc);
        } finally {
            small.release();
            grayFloat.release();
            spectrum.release();
        }
    }

    /**
     * Bins more than {@link #BAND_RADIUS} from the centre, measured in wrapped
     * coordinates: bin {@code x} is {@code min(x, N-x)} away from DC, because
     * indices past half the edge are the negative frequencies.
     */
    private static boolean[] buildOuterBand() {
        boolean[] band = new boolean[SPECTRUM_EDGE * SPECTRUM_EDGE];
        for (int y = 0; y < SPECTRUM_EDGE; y++) {
            int dy = Math.min(y, SPECTRUM_EDGE - y);
            for (int x = 0; x < SPECTRUM_EDGE; x++) {
                int dx = Math.min(x, SPECTRUM_EDGE - x);
                band[y * SPECTRUM_EDGE + x] = Math.hypot(dx, dy) > BAND_RADIUS;
            }
        }
        return band;
    }
}
