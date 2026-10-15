package io.mosip.liveness.device;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

/**
 * Deterministic, scriptable scene rendered by the {@link MockL0L1Device}:
 * a moving gradient background with a face-like subject that can blink.
 * Pixel content is plausible enough to exercise decode/conversion paths and
 * real-image backends in smoke tests; it is NOT a biometrically meaningful
 * face — signal truth always lives in the backend under test (e.g.
 * {@code MockLivenessBackend.Subject}).
 */
public final class SyntheticScene {

    private final int width;
    private final int height;

    // Scriptable state — tests drive these between frames.
    private volatile boolean facePresent = true;
    private volatile boolean blinking;
    private volatile double brightness = 0.85;   // 0..1 global multiplier
    private volatile double motionSpeed = 1.0;   // background scroll factor

    public SyntheticScene(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("scene size must be positive");
        }
        this.width = width;
        this.height = height;
    }

    /** Render the frame for the given wall-clock time. */
    public BufferedImage nextFrame(long timestampMillis) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawBackground(g, timestampMillis);
            if (facePresent) {
                drawFace(g, timestampMillis);
            }
            applyBrightness(img);
        } finally {
            g.dispose();
        }
        return img;
    }

    private void drawBackground(Graphics2D g, long t) {
        double phase = (t / 1000.0) * motionSpeed;
        int band = Math.max(8, height / 6);
        for (int y = 0; y < height; y += band) {
            float shade = 0.25f + 0.15f * (float) Math.sin(phase + y * 0.02);
            g.setColor(new Color(0.18f + shade * 0.3f, 0.20f + shade * 0.3f, 0.28f + shade * 0.3f));
            g.fillRect(0, y, width, band);
        }
    }

    private void drawFace(Graphics2D g, long t) {
        double cx = width / 2.0 + Math.sin(t / 700.0) * width * 0.01;
        double cy = height / 2.0 + Math.cos(t / 900.0) * height * 0.01;
        double fw = width * 0.34, fh = height * 0.52;

        g.setColor(new Color(0xE0, 0xB8, 0x94));
        g.fill(new Ellipse2D.Double(cx - fw / 2, cy - fh / 2, fw, fh));

        g.setColor(Color.DARK_GRAY);
        double eyeY = cy - fh * 0.12;
        double eyeDx = fw * 0.22;
        if (blinking) {
            g.setStroke(new BasicStroke(Math.max(2f, height / 120f)));
            g.drawLine((int) (cx - eyeDx), (int) eyeY, (int) (cx - eyeDx + fw * 0.12), (int) eyeY);
            g.drawLine((int) (cx + eyeDx - fw * 0.12), (int) eyeY, (int) (cx + eyeDx), (int) eyeY);
        } else {
            double er = fw * 0.055;
            g.fill(new Ellipse2D.Double(cx - eyeDx - er, eyeY - er, er * 2, er * 2));
            g.fill(new Ellipse2D.Double(cx + eyeDx - er, eyeY - er, er * 2, er * 2));
        }
        g.drawLine((int) (cx - fw * 0.10), (int) (cy + fh * 0.18),
                (int) (cx + fw * 0.10), (int) (cy + fh * 0.18));
    }

    private void applyBrightness(BufferedImage img) {
        double b = brightness;
        if (b >= 0.999 && b <= 1.001) {
            return;
        }
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int r = (int) Math.min(255, ((px[i] >> 16 & 0xFF)) * b);
            int gr = (int) Math.min(255, ((px[i] >> 8 & 0xFF)) * b);
            int bl = (int) Math.min(255, ((px[i] & 0xFF)) * b);
            px[i] = r << 16 | gr << 8 | bl;
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    // ------------------------------------------------------------ scripting

    public void setFacePresent(boolean present) { this.facePresent = present; }

    public void setBlinking(boolean blinking) { this.blinking = blinking; }

    public void setBrightness(double brightness) { this.brightness = brightness; }

    public void setMotionSpeed(double motionSpeed) { this.motionSpeed = motionSpeed; }

    public int width() { return width; }

    public int height() { return height; }
}
