package io.mosip.liveness.services;

import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.metrics.PipelineTimers;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

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
        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);

            // Screen-replay / moire proxy via high-frequency spectral energy
            freqRatio = frequencyEnergyRatio(gray);

            // Print-attack proxy via local texture variance
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
            Core.meanStdDev(laplacian, mean, stddev);
            textureVariance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
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
