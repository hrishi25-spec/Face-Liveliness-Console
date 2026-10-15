package io.mosip.liveness.device;

import io.mosip.liveness.core.Frame;

import java.awt.image.BufferedImage;

/**
 * Conversion helpers from decoded images (what {@code ImageIO} or a camera
 * SDK produces) to the frame payload formats the engine supports. Device
 * adapters use these to normalize vendor output; the engine never does.
 */
public final class PixelFormats {

    private PixelFormats() {
    }

    /** Row-major RGB_888 payload (3 bytes/pixel, R,G,B order). */
    public static byte[] rgb888(BufferedImage image) {
        BufferedImage rgb = toRgbType(image);
        int w = rgb.getWidth(), h = rgb.getHeight();
        byte[] out = new byte[w * h * 3];
        int[] px = rgb.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0, o = 0; i < px.length; i++, o += 3) {
            int p = px[i];
            out[o] = (byte) (p >> 16 & 0xFF);
            out[o + 1] = (byte) (p >> 8 & 0xFF);
            out[o + 2] = (byte) (p & 0xFF);
        }
        return out;
    }

    /** Single-channel luminance (RGB_GRAY), BT.601 luma. */
    public static byte[] gray8(BufferedImage image) {
        BufferedImage rgb = toRgbType(image);
        int w = rgb.getWidth(), h = rgb.getHeight();
        byte[] out = new byte[w * h];
        int[] px = rgb.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            out[i] = (byte) y(px[i]);
        }
        return out;
    }

    /**
     * NV21 (Android camera default): Y plane followed by interleaved V,U
     * quarter-resolution chroma. Useful when an adapter wants to hand the
     * backend exactly what Android's CameraX would deliver.
     */
    public static byte[] nv21(BufferedImage image) {
        BufferedImage rgb = toRgbType(image);
        int w = rgb.getWidth(), h = rgb.getHeight();
        if ((w & 1) != 0 || (h & 1) != 0) {
            throw new IllegalArgumentException("NV21 requires even dimensions: " + w + "x" + h);
        }
        byte[] out = new byte[w * h + (w / 2) * (h / 2) * 2];
        int[] px = rgb.getRGB(0, 0, w, h, null, 0, w);
        int yIndex = 0;
        int uvIndex = w * h;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int p = px[row * w + col];
                int r = p >> 16 & 0xFF, g = p >> 8 & 0xFF, bl = p & 0xFF;
                int y = y(p);
                out[yIndex++] = (byte) y;
                if ((row & 1) == 0 && (col & 1) == 0) {
                    int u = clamp((-169 * r - 331 * g + 500 * bl) / 256 + 128);
                    int v = clamp((500 * r - 419 * g - 81 * bl) / 256 + 128);
                    out[uvIndex++] = (byte) v; // NV21 stores V before U
                    out[uvIndex++] = (byte) u;
                }
            }
        }
        return out;
    }

    /** Expected payload size for a format/dimension pair — adapters validate against this. */
    public static int bytesFor(Frame.Format format, int width, int height) {
        return switch (format) {
            case RGB_GRAY -> width * height;
            case RGB_888 -> width * height * 3;
            case NV21, YUV420 -> width * height * 3 / 2;
        };
    }

    private static int y(int argb) {
        int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        return clamp((77 * r + 150 * g + 29 * b) / 256);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static BufferedImage toRgbType(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }
}
