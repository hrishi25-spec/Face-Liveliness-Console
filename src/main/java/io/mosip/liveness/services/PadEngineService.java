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
 */
@Service
public class PadEngineService {

    private static final double FREQ_ENERGY_ATTACK_THRESHOLD = 0.35;

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
        Mat grayFloat = new Mat();
        Mat fft = new Mat();
        Mat magnitude = new Mat();
        Mat laplacian = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        double textureVariance;
        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
            gray.convertTo(grayFloat, CvType.CV_32F);

            // Screen-replay / moire proxy via high-frequency FFT energy
            Core.dft(grayFloat, fft);
            Core.magnitude(fft, fft, magnitude);

            // Print-attack proxy via local texture variance
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
            Core.meanStdDev(laplacian, mean, stddev);
            textureVariance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
        } finally {
            // Six native buffers per frame; a throw here must not leave them behind.
            gray.release();
            grayFloat.release();
            fft.release();
            magnitude.release();
            laplacian.release();
            mean.release();
            stddev.release();
        }

        // Evaluate thresholds
        // For FFT-based detection, use a simplified energy ratio
        // In production, this would use proper frequency analysis
        double freqRatio = 0.1; // Placeholder — real FFT analysis goes here

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
}
