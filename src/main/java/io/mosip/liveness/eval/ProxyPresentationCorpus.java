package io.mosip.liveness.eval;

import io.mosip.liveness.device.SyntheticScene;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Proxy attack corpus for threshold calibration.
 *
 * <p>No recorded physical presentations (real prints, real screen replays)
 * exist in this repository (docs/status-report.md §4, Finding 5 — the corpus
 * gap). Until
 * such captures exist, this generator renders {@link SyntheticScene} frames and
 * degrades them the way a printed photo or a screen replay degrades a capture:</p>
 * <ul>
 *   <li><b>Printed photo</b> — paper border, flattened contrast, halftone
 *       hatch, slight blur</li>
 *   <li><b>Screen replay</b> — scan lines, blue screen glow, specular glare</li>
 * </ul>
 *
 * <p>Every result is labelled <b>proxy</b> by the caller: these are simulated
 * degradations of synthetic scenes, not physical attacks, so APCER computed
 * from them is directional — it will overstate real-world performance,
 * especially for video replay. Its purpose is to keep the calibration pipeline
 * runnable end-to-end and reusable with real captures later (drop-in the same
 * {@link FrameScorer}).</p>
 *
 * <p>Frames in which the scorer cannot detect exactly one face are reported as
 * {@code null} and dropped: a corpus the detection pipeline cannot even see
 * would otherwise fabricate a perfect (and meaningless) APCER of 0.</p>
 */
public final class ProxyPresentationCorpus {

    /** Presentation attack types simulated here (ISO/IEC 30107 species). */
    public enum AttackKind { PRINTED_PHOTO, SCREEN_REPLAY }

    /**
     * Scores one frame. Returns {@code null} when the frame contains no single
     * detectable face (the frame is then excluded from the corpus).
     */
    @FunctionalInterface
    public interface FrameScorer {
        Double score(Mat frame);
    }

    /** One synthetic presentation: its kind and the per-frame scores. */
    public record Window(AttackKind kind, List<Double> scores) { }

    private ProxyPresentationCorpus() { }

    /**
     * Generates {@code windowsPerKind} windows of {@code framesPerWindow}
     * scores for each {@link AttackKind}.
     */
    public static List<Window> generate(FrameScorer scorer,
                                        int windowsPerKind,
                                        int framesPerWindow,
                                        int width,
                                        int height) {
        List<Window> out = new ArrayList<>();
        for (AttackKind kind : AttackKind.values()) {
            for (int w = 0; w < windowsPerKind; w++) {
                SyntheticScene scene = new SyntheticScene(width, height);
                List<Double> scores = new ArrayList<>();
                long baseTime = 1_000L + w * 17_000L;
                for (int f = 0; f < framesPerWindow; f++) {
                    BufferedImage rendered = scene.nextFrame(baseTime + f * 120L);
                    Mat frame = toBgrMat(rendered);
                    try {
                        degrade(frame, kind, w, f);
                        Double score = scorer.score(frame);
                        if (score != null) scores.add(score);
                    } finally {
                        frame.release();
                    }
                }
                out.add(new Window(kind, scores));
            }
        }
        return out;
    }

    static Mat toBgrMat(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] bgr = new byte[w * h * 3];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            bgr[i * 3] = (byte) (p & 0xFF);              // B
            bgr[i * 3 + 1] = (byte) ((p >> 8) & 0xFF);   // G
            bgr[i * 3 + 2] = (byte) ((p >> 16) & 0xFF);  // R
        }
        Mat m = new Mat(h, w, CvType.CV_8UC3);
        m.put(0, 0, bgr);
        return m;
    }

    static void degrade(Mat frame, AttackKind kind, int windowIndex, int frameIndex) {
        switch (kind) {
            case PRINTED_PHOTO -> degradePrintedPhoto(frame, windowIndex, frameIndex);
            case SCREEN_REPLAY -> degradeScreenReplay(frame, frameIndex);
        }
    }

    private static void degradePrintedPhoto(Mat frame, int windowIndex, int frameIndex) {
        int w = frame.cols();
        int h = frame.rows();
        int border = Math.max(4, Math.min(w, h) / 24);

        // Paper border: the white margin of a printed photo.
        Imgproc.rectangle(frame, new Point(0, 0), new Point(w - 1, h - 1),
                new Scalar(245, 245, 245), border);

        // Flattened contrast + slight brightness lift (ink on paper).
        Mat flattened = new Mat();
        Core.convertScaleAbs(frame, flattened, 0.78, 28);
        flattened.copyTo(frame);
        flattened.release();

        // Halftone hatch: fine diagonal lines, phase varies per frame/window so
        // the pattern is not identical across presentations.
        int period = 3 + (windowIndex + frameIndex) % 2;
        Scalar ink = new Scalar(40, 40, 40);
        for (int y = -h; y < h; y += period) {
            Imgproc.line(frame, new Point(0, y + (frameIndex % period)),
                    new Point(w, y + w + (frameIndex % period)), ink, 1);
        }

        // Print blur.
        Imgproc.GaussianBlur(frame, frame, new org.opencv.core.Size(3, 3), 0);
    }

    private static void degradeScreenReplay(Mat frame, int frameIndex) {
        int w = frame.cols();
        int h = frame.rows();

        // Scan lines (LCD/OLED pixel structure).
        for (int y = frameIndex % 3; y < h; y += 3) {
            Imgproc.line(frame, new Point(0, y), new Point(w, y), new Scalar(0, 0, 0), 1);
        }

        // Blue screen glow: raise the blue channel, dampen red.
        List<Mat> channels = new ArrayList<>();
        Core.split(frame, channels);
        Mat blue = channels.get(2);
        Mat blueBoosted = new Mat();
        Core.convertScaleAbs(blue, blueBoosted, 1.12, 6);
        blueBoosted.copyTo(blue);
        Mat red = channels.get(0);
        Mat redDamped = new Mat();
        Core.convertScaleAbs(red, redDamped, 0.92, 0);
        redDamped.copyTo(red);
        Core.merge(channels, frame);
        for (Mat c : channels) c.release();
        blueBoosted.release();
        redDamped.release();

        // Specular glare from the glass.
        int cx = w / 3 + (frameIndex * 7) % Math.max(1, w / 3);
        int cy = h / 4;
        Mat glare = new Mat(frame.size(), frame.type(), new Scalar(0, 0, 0));
        Imgproc.circle(glare, new Point(cx, cy), Math.min(w, h) / 6,
                new Scalar(70, 70, 70), -1);
        Imgproc.GaussianBlur(glare, glare, new org.opencv.core.Size(41, 41), 0);
        Core.add(frame, glare, frame);
        glare.release();
    }

    /** Rect helper kept for callers building their own degradations. */
    static Rect innerFrame(int w, int h, int border) {
        return new Rect(border, border, w - 2 * border, h - 2 * border);
    }
}
