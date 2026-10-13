package io.mosip.liveness.services;

import io.mosip.liveness.models.enums.ChallengeType;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Face liveness engine service (passive scoring + active challenge validation).
 * Maps to the Python framework's liveness_engine.py.
 */
@Service
public class LivenessEngineService {

    /**
     * Observe face in frame: detect face, check quality, return observation.
     */
    public FaceObservation observeFace(Mat frame, ImageUtils imageUtils) {
        Rect[] faces = imageUtils.detectFaces(frame);
        if (faces.length == 0) {
            return new FaceObservation(false, false, null, null);
        }
        if (faces.length > 1) {
            return new FaceObservation(true, true, null, null);
        }
        Rect face = faces[0];
        double quality = 0.5 * imageUtils.sharpnessScore(frame) + 0.5 * imageUtils.brightnessScore(frame);
        return new FaceObservation(true, false, quality,
                new int[]{face.x, face.y, face.width, face.height});
    }

    /**
     * Score passive liveness for a frame (higher = more alive).
     */
    public double scorePassive(Mat frame, FaceObservation observation, ImageUtils imageUtils) {
        if (!observation.faceDetected() || observation.multipleFaces()) {
            return 0.0;
        }
        int[] bbox = observation.bbox();
        Rect roi = new Rect(bbox[0], bbox[1], bbox[2], bbox[3]);
        Mat gray = new Mat();
        Mat faceRoi = null;
        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
            faceRoi = gray.submat(roi);
            int eyeCount = imageUtils.detectEyes(faceRoi).length;
            double eyeSymmetryScore = eyeCount >= 2 ? 1.0 : 0.4;
            double score = 0.4 * (observation.faceQuality() != null ? observation.faceQuality() : 0.0)
                    + 0.4 * eyeSymmetryScore
                    + 0.2 * imageUtils.sharpnessScore(frame);
            return Math.max(0.0, Math.min(1.0, score));
        } finally {
            // finally, not straight-line code: the eye cascade is the call most
            // likely to throw (native failure), and the buffers must not depend
            // on it returning normally.
            if (faceRoi != null) faceRoi.release();
            gray.release();
        }
    }

    /**
     * Floor for how far the face box must travel, in pixels, for a head-turn
     * challenge. The effective requirement scales with face size (see
     * {@link #TURN_WIDTH_FRACTION}); this floor keeps small/distant faces workable.
     */
    private static final double MIN_TURN_DELTA_PX = 12.0;

    /** Floor for gaze-direction travel, in pixels. */
    private static final double MIN_GAZE_DELTA_PX = 8.0;

    /**
     * Head-turn travel required, as a fraction of the detected face width.
     * A fixed pixel threshold behaved differently on every camera and face size:
     * it was nearly unreachable on a low-resolution feed and trivially satisfied
     * (by detection jitter) on a close-up. A real head turn moves the box by
     * roughly a fifth of the face width, so make the bar proportional.
     */
    private static final double TURN_WIDTH_FRACTION = 0.20;

    /** Gaze-direction travel required, as a fraction of the detected face width. */
    private static final double GAZE_WIDTH_FRACTION = 0.12;

    /**
     * Validate active challenge (blink, smile, turn, etc.) across a sequence of frames.
     */
    public boolean validateActive(ChallengeType challengeType, List<Mat> frames, ImageUtils imageUtils) {
        // Frames where no face can be found are dropped rather than failing the
        // whole challenge. Haar tracking regularly drops the face for a frame or
        // two mid-turn, and the previous "if any frame has no face, return false"
        // rule rejected exactly the genuine turns we want to accept. Enough frames
        // must still show a face for the measurement to mean anything.
        List<Mat> usable = new ArrayList<>();
        List<int[]> boxes = new ArrayList<>();
        for (Mat f : frames) {
            FaceObservation obs = observeFace(f, imageUtils);
            if (obs.bbox() == null) continue;
            usable.add(f);
            boxes.add(obs.bbox());
        }
        if (usable.size() < 2) return false;

        double faceWidth = medianWidth(boxes);
        double turnDelta = Math.max(MIN_TURN_DELTA_PX, TURN_WIDTH_FRACTION * faceWidth);
        double gazeDelta = Math.max(MIN_GAZE_DELTA_PX, GAZE_WIDTH_FRACTION * faceWidth);
        double excursion = peakExcursion(boxes);

        return switch (challengeType) {
            case BLINK -> checkBlink(usable, boxes, imageUtils);

            // Frames arrive as raw, un-mirrored camera pixels. A camera sees the
            // subject the way another person does: the subject's own LEFT appears
            // on the image's RIGHT. Turning one's head to one's own left therefore
            // moves the face box toward LARGER x.
            //
            // Challenge labels are egocentric — the UI tells the person "turn
            // left", meaning the person's own left — so they map as below. These
            // were previously swapped, which made every instruction read backwards.
            case TURN_LEFT -> excursion > turnDelta;
            case TURN_RIGHT -> excursion < -turnDelta;
            case LOOK_LEFT -> excursion > gazeDelta;
            case LOOK_RIGHT -> excursion < -gazeDelta;

            // Vertical gaze cannot be recovered from horizontal box movement.
            // Fail closed rather than passing on an unrelated sideways motion.
            case LOOK_UP, LOOK_DOWN -> false;

            case SMILE -> checkSmile(usable, boxes, imageUtils);

            // LOOK_DIRECTION is direction-agnostic by contract.
            default -> Math.abs(excursion) > gazeDelta;
        };
    }

    /**
     * Signed horizontal excursion of the face box, relative to the baseline frame
     * (the first frame in which a face was found, when the subject is neutral).
     *
     * <p>The value with the largest magnitude is returned, not the last frame's
     * displacement: a person who turns and returns to centre — or who reaches the
     * pose part-way through the burst — still registers the turn, whereas
     * measuring only first-vs-last frames cancelled it out.</p>
     *
     * <p>Coordinates are the raw image's own: x grows to the image's right.</p>
     */
    private static double peakExcursion(List<int[]> boxes) {
        int baselineX = boxes.get(0)[0];
        double peak = 0.0;
        for (int[] b : boxes) {
            double dx = b[0] - baselineX;
            if (Math.abs(dx) > Math.abs(peak)) peak = dx;
        }
        return peak;
    }

    /** Median face-box width, used to scale the movement thresholds. */
    private static double medianWidth(List<int[]> boxes) {
        double[] widths = boxes.stream().mapToDouble(b -> b[2]).sorted().toArray();
        int n = widths.length;
        return n % 2 == 1 ? widths[n / 2] : 0.5 * (widths[n / 2 - 1] + widths[n / 2]);
    }

    private boolean checkBlink(List<Mat> frames, List<int[]> boxes, ImageUtils imageUtils) {
        boolean hadClosed = false;
        for (int i = 0; i < frames.size(); i++) {
            int[] b = boxes.get(i);
            Rect roi = new Rect(b[0], b[1], b[2], b[3]);
            Mat gray = new Mat();
            Mat faceRoi = null;
            int eyeCount;
            try {
                Imgproc.cvtColor(frames.get(i), gray, Imgproc.COLOR_BGR2GRAY);
                faceRoi = gray.submat(roi);
                eyeCount = imageUtils.detectEyes(faceRoi).length;
            } finally {
                if (faceRoi != null) faceRoi.release();
                gray.release();
            }
            if (eyeCount == 0) {
                hadClosed = true;
            } else if (hadClosed) {
                return true;
            }
        }
        return false;
    }

    private boolean checkSmile(List<Mat> frames, List<int[]> boxes, ImageUtils imageUtils) {
        double minVar = Double.MAX_VALUE;
        double maxVar = Double.MIN_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            int[] b = boxes.get(i);
            int mouthY = b[1] + (int)(b[3] * 0.6);
            int mouthH = (int)(b[3] * 0.4);
            if (mouthY + mouthH > frames.get(i).rows()) continue;

            Mat gray = new Mat();
            Mat laplacian = new Mat();
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble stddev = new MatOfDouble();
            Mat mouthRoi = null;
            try {
                Imgproc.cvtColor(frames.get(i), gray, Imgproc.COLOR_BGR2GRAY);
                mouthRoi = gray.submat(new Rect(b[0], mouthY, b[2], mouthH));
                Imgproc.Laplacian(mouthRoi, laplacian, CvType.CV_64F);
                Core.meanStdDev(laplacian, mean, stddev);   // mean is required by the API, unused
                double variance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0];
                minVar = Math.min(minVar, variance);
                maxVar = Math.max(maxVar, variance);
            } finally {
                if (mouthRoi != null) mouthRoi.release();
                gray.release();
                laplacian.release();
                mean.release();
                stddev.release();
            }
        }
        return (maxVar - minVar) > 50;
    }

    // ---- observation data class ----

    public record FaceObservation(
            boolean faceDetected,
            boolean multipleFaces,
            Double faceQuality,
            int[] bbox  // [x, y, w, h]
    ) {}
}
